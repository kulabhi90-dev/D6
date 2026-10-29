package com.example;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Reject deletes data in D5, so the whole app sits behind HTTP Basic login.
 * App settings D6_USER and D6_PASSWORD are required; with them missing the app stays locked.
 */
@WebFilter("/*")
public class AuthFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;
               if (req.getRequestURI().equals(req.getContextPath() + "/api/intake")) {
            chain.doFilter(request, response);
            return;
        }
        String expectedUser = env("D6_USER");
        String expectedPass = env("D6_PASSWORD");
        if (expectedUser == null || expectedPass == null) {
            resp.setStatus(503);
            resp.setContentType("text/plain; charset=UTF-8");
            resp.getWriter().write("D6 is locked: set the D6_USER and D6_PASSWORD application settings.");
            return;
        }

        String header = req.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Basic ", 0, 6)) {
            try {
                String decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()),
                        StandardCharsets.UTF_8);
                int colon = decoded.indexOf(':');
                if (colon > 0) {
                    String u = decoded.substring(0, colon);
                    String p = decoded.substring(colon + 1);
                    boolean userOk = same(u, expectedUser);
                    boolean passOk = same(p, expectedPass);
                    if (userOk & passOk) {
                        req.setAttribute("currentUser", u);
                        chain.doFilter(request, response);
                        return;
                    }
                }
            } catch (IllegalArgumentException ignored) {
                // bad base64 -> fall through to 401
            }
        }

        resp.setHeader("WWW-Authenticate", "Basic realm=\"D6 Review\", charset=\"UTF-8\"");
        resp.setStatus(401);
        resp.setContentType("text/plain; charset=UTF-8");
        resp.getWriter().write("Login required.");
    }

    private static boolean same(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String env(String key) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? null : v;
    }
}
