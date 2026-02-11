package com.arcbank.cbs.transaccion.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class OriginSecretFilter implements Filter {

    @Value("${app.apim.origin-secret:change-me}")
    private String expectedSecret;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
            FilterChain chain) throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;

        // El health check no requiere el secreto para que el ALB pueda verificar el
        // estado
        String path = httpRequest.getRequestURI();
        if (path.contains("/health") || path.contains("/actuator")) {
            chain.doFilter(request, response);
            return;
        }

        String originSecret = httpRequest.getHeader("x-origin-secret");

        if (expectedSecret != null && !expectedSecret.equals("change-me") && !expectedSecret.equals(originSecret)) {
            HttpServletResponse httpResponse = (HttpServletResponse) response;
            httpResponse.setStatus(HttpServletResponse.SC_FORBIDDEN);
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write("{\"error\":\"Acceso directo no permitido o secreto invalido\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
