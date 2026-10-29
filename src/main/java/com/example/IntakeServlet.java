package com.example;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * POST /api/intake  - called by D5 whenever a new entry is saved.
 * Header:  Authorization: Bearer <D6_INTAKE_TOKEN>
 * Body:    {"id":12,"fullName":"..","emailAddress":"..","city":"..","gender":".."}
 * Safe to call twice for the same id (it updates instead of duplicating).
 */
@WebServlet("/api/intake")
public class IntakeServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = Logger.getLogger(IntakeServlet.class.getName());
    private static final Gson GSON = new Gson();
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String expected = System.getenv("D6_INTAKE_TOKEN");
        if (expected == null || expected.isBlank()) { send(resp, 503, "D6_INTAKE_TOKEN is not set on D6"); return; }

        String header = req.getHeader("Authorization");
        String given = (header != null && header.startsWith("Bearer ")) ? header.substring(7).trim() : "";
        if (!MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8),
                expected.trim().getBytes(StandardCharsets.UTF_8))) {
            send(resp, 401, "Bad or missing token");
            return;
        }

        req.setCharacterEncoding("UTF-8");
        JsonObject b;
        try {
            b = JsonParser.parseReader(req.getReader()).getAsJsonObject();
        } catch (Exception e) {
            send(resp, 400, "Body must be valid JSON");
            return;
        }

        int id;
        String name = str(b, "fullName"), email = str(b, "emailAddress"), city = str(b, "city"), gender = str(b, "gender");
        try {
            id = b.get("id").getAsInt();
        } catch (Exception e) {
            send(resp, 400, "id must be a number");
            return;
        }
        if (name.isEmpty() || email.isEmpty() || city.isEmpty() || gender.isEmpty()
                || name.length() > 100 || email.length() > 150 || city.length() > 100 || gender.length() > 50
                || !EMAIL.matcher(email).matches()) {
            send(resp, 400, "fullName, emailAddress, city and gender are required and must fit the column sizes");
            return;
        }

        try (Connection con = Db.getConnection()) {
            int n;
            try (PreparedStatement up = con.prepareStatement(
                    "UPDATE dbo.d6_entries SET full_name = ?, email_address = ?, city = ?, gender = ? WHERE d5_id = ?")) {
                up.setString(1, name); up.setString(2, email); up.setString(3, city); up.setString(4, gender);
                up.setInt(5, id);
                n = up.executeUpdate();
            }
            if (n == 0) {
                try (PreparedStatement ins = con.prepareStatement(
                        "INSERT INTO dbo.d6_entries (d5_id, full_name, email_address, city, gender) VALUES (?, ?, ?, ?, ?)")) {
                    ins.setInt(1, id); ins.setString(2, name); ins.setString(3, email);
                    ins.setString(4, city); ins.setString(5, gender);
                    ins.executeUpdate();
                }
            }
            resp.setStatus(200);
            resp.setContentType("application/json");
            resp.getWriter().write(GSON.toJson(Map.of("ok", true, "id", id)));
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Intake failed", e);
            send(resp, 500, "Database error");
        }
    }

    private static String str(JsonObject o, String k) {
        return (o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString().trim() : "";
    }

    private static void send(HttpServletResponse resp, int status, String msg) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write(GSON.toJson(Map.of("error", msg)));
    }
}
