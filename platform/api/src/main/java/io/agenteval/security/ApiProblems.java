package io.agenteval.security;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

public final class ApiProblems {
    private ApiProblems() {}
    public static void write(HttpServletResponse response,int status,String code) throws IOException {
        response.setStatus(status); response.setContentType("application/problem+json"); response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"status\":"+status+",\"code\":\""+code+"\",\"detail\":\"Request rejected\",\"request_id\":\""+UUID.randomUUID()+"\"}");
    }
}
