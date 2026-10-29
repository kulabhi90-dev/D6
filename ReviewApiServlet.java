package com.example;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *  GET  /api/entries                -> every entry in D5, each with its review status
 *  GET  /api/entries/{id}           -> one entry
 *  POST /api/entries/{id}/review    -> mark the entry as reviewed (kept in D6's own table)
 *  POST /api/entries/{id}/reject    -> DELETE the entry from D5's database, then forget it here
 *
 *  POST calls must carry the header  X-Requested-With: D6  (blocks cross-site form posts).
 */
@WebServlet("/api/entries/*")
public class ReviewApiServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = Logger.getLogger(ReviewApiServlet.class.getName());
    private static final Gson GSON = new Gson();

    // ------------------------------------------------------------------ GET
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] p = parts(req);
        try {
            if (p.length == 0) {
                list(resp);
            } else if (p.length == 1) {
                Integer id = parseId(p[0]);
                if (id == null) { send(resp, 400, err("id must be a number")); return; }
                one(id, resp);
            } else {
                send(resp, 404, err("Not found"));
            }
        } catch (Exception e) {
            fail(resp, "GET failed", e);
        }
    }

    // ----------------------------------------------------------------- POST
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!"D6".equals(req.getHeader("X-Requested-With"))) {
            send(resp, 400, err("Missing X-Requested-With header"));
            return;
        }
        String[] p = parts(req);
        if (p.length != 2) { send(resp, 404, err("Use /api/entries/{id}/review or /reject")); return; }
        Integer id = parseId(p[0]);
        if (id == null) { send(resp, 400, err("id must be a number")); return; }

        String user = String.valueOf(req.getAttribute("currentUser"));
        try {
            switch (p[1]) {
                case "review" -> review(id, user, resp);
                case "reject" -> reject(id, resp);
                default -> send(resp, 404, err("Unknown action '" + p[1] + "'"));
            }
        } catch (Exception e) {
            fail(resp, "POST " + p[1] + " failed", e);
        }
    }

    // -------------------------------------------------------------- actions
    private void list(HttpServletResponse resp) throws Exception {
        D5Client.Response r = D5Client.get("/api/users");
        if (r.status() != 200) { send(resp, 502, err("D5 answered " + r.status() + " - check D5_TOKEN")); return; }

        JsonArray arr = JsonParser.parseString(r.body()).getAsJsonArray();
        Map<Integer, String[]> statuses = loadStatuses();
        for (JsonElement el : arr) {
            JsonObject o = el.getAsJsonObject();
            applyStatus(o, statuses.get(o.get("id").getAsInt()));
        }
        send(resp, 200, arr);
    }

    private void one(int id, HttpServletResponse resp) throws Exception {
        D5Client.Response r = D5Client.get("/api/users/" + id);
        if (r.status() == 404) { send(resp, 404, err("Entry not found - it may already have been rejected")); return; }
        if (r.status() != 200) { send(resp, 502, err("D5 answered " + r.status())); return; }

        JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
        applyStatus(o, loadStatuses().get(id));
        send(resp, 200, o);
    }

    private void review(int id, String user, HttpServletResponse resp) throws Exception {
        D5Client.Response r = D5Client.get("/api/users/" + id);
        if (r.status() == 404) { send(resp, 404, err("Entry not found - it may already have been rejected")); return; }
        if (r.status() != 200) { send(resp, 502, err("D5 answered " + r.status())); return; }

        try (Connection con = Db.getConnection()) {
            int n;
            try (PreparedStatement up = con.prepareStatement(
                    "UPDATE dbo.review_status SET status = 'REVIEWED', reviewed_by = ?, "
                            + "reviewed_at = SYSUTCDATETIME() WHERE user_id = ?")) {
                up.setString(1, user);
                up.setInt(2, id);
                n = up.executeUpdate();
            }
            if (n == 0) {
                try (PreparedStatement ins = con.prepareStatement(
                        "INSERT INTO dbo.review_status (user_id, status, reviewed_by) VALUES (?, 'REVIEWED', ?)")) {
                    ins.setInt(1, id);
                    ins.setString(2, user);
                    ins.executeUpdate();
                }
            }
        }
        one(id, resp);
    }

    private void reject(int id, HttpServletResponse resp) throws Exception {
        // 1) delete in D5 first - if that fails we keep everything as it was
        D5Client.Response r = D5Client.delete("/api/users/" + id);
        if (r.status() != 200 && r.status() != 404) {
            send(resp, 502, err("D5 refused the delete (" + r.status() + "). Nothing was changed."));
            return;
        }
        // 2) forget any review note here
        try (Connection con = Db.getConnection();
             PreparedStatement ps = con.prepareStatement("DELETE FROM dbo.review_status WHERE user_id = ?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rejected", id);
        out.put("alreadyGone", r.status() == 404);
        send(resp, 200, out);
    }

    // -------------------------------------------------------------- helpers
    /** user_id -> {status, reviewedBy, reviewedAt} */
    private static Map<Integer, String[]> loadStatuses() throws Exception {
        Map<Integer, String[]> m = new HashMap<>();
        try (Connection con = Db.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT user_id, status, reviewed_by, reviewed_at FROM dbo.review_status");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                m.put(rs.getInt("user_id"), new String[]{
                        rs.getString("status"), rs.getString("reviewed_by"), rs.getString("reviewed_at")});
            }
        }
        return m;
    }

    private static void applyStatus(JsonObject o, String[] s) {
        o.addProperty("status", s == null ? "PENDING" : s[0]);
        if (s != null) {
            o.addProperty("reviewedBy", s[1]);
            o.addProperty("reviewedAt", s[2]);
        }
    }

    private static String[] parts(HttpServletRequest req) {
        String path = req.getPathInfo();
        if (path == null || path.length() <= 1) return new String[0];
        return path.substring(1).split("/");
    }

    private static Integer parseId(String s) {
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void fail(HttpServletResponse resp, String what, Exception e) throws IOException {
        LOG.log(Level.SEVERE, what, e);
        if (e instanceof IllegalStateException) {
            send(resp, 503, err(e.getMessage()));
        } else {
            send(resp, 500, err("Server error - see the app log"));
        }
    }

    private static Map<String, Object> err(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        return m;
    }

    private static void send(HttpServletResponse resp, int status, Object payload) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write(GSON.toJson(payload));
    }
}
