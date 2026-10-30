package com.example;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@WebServlet("/api/entries/*")
public class ReviewApiServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = Logger.getLogger(ReviewApiServlet.class.getName());
    private static final Gson GSON = new Gson();
    private static final String COLS =
            "d5_id, full_name, email_address, city, gender, status, received_at, reviewed_by, reviewed_at";

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] p = parts(req);
        try {
            if (p.length == 0) {
                syncFromD5();
                JsonArray arr = new JsonArray();
                try (Connection con = Db.getConnection();
                     PreparedStatement ps = con.prepareStatement(
                             "SELECT " + COLS + " FROM dbo.d6_entries WHERE status = 'PENDING' ORDER BY full_name, d5_id");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) arr.add(row(rs));
                }
                send(resp, 200, arr);
            } else if (p.length == 1) {
                Integer id = parseId(p[0]);
                if (id == null) { send(resp, 400, err("id must be a number")); return; }
                JsonObject o = fetchOne(id);
                if (o == null) send(resp, 404, err("Entry not found - it may already have been rejected"));
                else send(resp, 200, o);
            } else {
                send(resp, 404, err("Not found"));
            }
        } catch (Exception e) {
            fail(resp, "GET failed", e);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!"D6".equals(req.getHeader("X-Requested-With"))) {
            send(resp, 400, err("Missing X-Requested-With header"));
            return;
        }
        String[] p = parts(req);
        if (p.length != 2) { send(resp, 404, err("Use /api/entries/{id}/accept or /reject")); return; }
        Integer id = parseId(p[0]);
        if (id == null) { send(resp, 400, err("id must be a number")); return; }

        try {
            switch (p[1]) {
                case "accept" -> accept(id, String.valueOf(req.getAttribute("currentUser")), resp);
                case "reject" -> reject(id, resp);
                default -> send(resp, 404, err("Unknown action '" + p[1] + "'"));
            }
        } catch (Exception e) {
            fail(resp, "POST " + p[1] + " failed", e);
        }
    }

    /**
     * Copies every entry saved in D5 (dbo.users) that D6 does not have yet into dbo.d6_entries.
     * Covers old entries and any entry the push from D5 missed. Needs D5 and D6 on the same database.
     * A failure here is logged but never stops the list from loading.
     */
    private static void syncFromD5() {
        String sql = "INSERT INTO dbo.d6_entries (d5_id, full_name, email_address, city, gender) "
                + "SELECT u.id, u.full_name, u.email_address, u.city, ISNULL(NULLIF(u.gender, ''), 'Not given') "
                + "FROM dbo.users u "
                + "WHERE NOT EXISTS (SELECT 1 FROM dbo.d6_entries e WHERE e.d5_id = u.id)";
        try (Connection con = Db.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            int n = ps.executeUpdate();
            if (n > 0) LOG.info("Synced " + n + " new entries from D5 into d6_entries");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Sync from D5 failed (list will show what D6 already has)", e);
        }
    }

    private void accept(int id, String user, HttpServletResponse resp) throws Exception {
        int n;
        try (Connection con = Db.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "UPDATE dbo.d6_entries SET status = 'ACCEPTED', reviewed_by = ?, reviewed_at = SYSUTCDATETIME() "
                             + "WHERE d5_id = ?")) {
            ps.setString(1, user);
            ps.setInt(2, id);
            n = ps.executeUpdate();
        }
        if (n == 0) { send(resp, 404, err("Entry not found - it may already have been rejected")); return; }
        send(resp, 200, fetchOne(id));
    }

    private void reject(int id, HttpServletResponse resp) throws Exception {
        if (fetchOne(id) == null) { send(resp, 404, err("Entry not found - it may already have been rejected")); return; }

        D5Client.Response r = D5Client.delete("/api/users/" + id);
        if (r.status() != 200 && r.status() != 404) {
            send(resp, 502, err("D5 refused the delete (" + r.status() + "). Nothing was changed."));
            return;
        }
        try (Connection con = Db.getConnection();
             PreparedStatement ps = con.prepareStatement("DELETE FROM dbo.d6_entries WHERE d5_id = ?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rejected", id);
        out.put("alreadyGoneInD5", r.status() == 404);
        send(resp, 200, out);
    }

    private static JsonObject fetchOne(int id) throws Exception {
        try (Connection con = Db.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT " + COLS + " FROM dbo.d6_entries WHERE d5_id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? row(rs) : null;
            }
        }
    }

    private static JsonObject row(ResultSet rs) throws Exception {
        JsonObject o = new JsonObject();
        o.addProperty("id", rs.getInt("d5_id"));
        o.addProperty("fullName", rs.getString("full_name"));
        o.addProperty("emailAddress", rs.getString("email_address"));
        o.addProperty("city", rs.getString("city"));
        o.addProperty("gender", rs.getString("gender"));
        o.addProperty("status", rs.getString("status"));
        o.addProperty("receivedAt", rs.getString("received_at"));
        o.addProperty("reviewedBy", rs.getString("reviewed_by"));
        o.addProperty("reviewedAt", rs.getString("reviewed_at"));
        return o;
    }

    private static String[] parts(HttpServletRequest req) {
        String path = req.getPathInfo();
        if (path == null || path.length() <= 1) return new String[0];
        return path.substring(1).split("/");
    }

    private static Integer parseId(String s) {
        try { return Integer.valueOf(s); } catch (NumberFormatException e) { return null; }
    }

    private static void fail(HttpServletResponse resp, String what, Exception e) throws IOException {
        LOG.log(Level.SEVERE, what, e);
        if (e instanceof IllegalStateException) send(resp, 503, err(e.getMessage()));
        else send(resp, 500, err("Server error - see the app log"));
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
