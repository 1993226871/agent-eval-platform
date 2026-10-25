package io.agenteval.api;
import io.agenteval.identity.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.dao.DuplicateKeyException;
import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ScopeViolationException.class) ResponseEntity<ProblemDetail> scope() { return problem(404,"NOT_FOUND"); }
    @ExceptionHandler(IdentityService.InvalidPasswordException.class) ResponseEntity<ProblemDetail> password() { return problem(400,"INVALID_PASSWORD"); }
    @ExceptionHandler({IllegalArgumentException.class,org.springframework.http.converter.HttpMessageNotReadableException.class}) ResponseEntity<ProblemDetail> invalid() { return problem(400,"INVALID_REQUEST"); }
    @ExceptionHandler(DuplicateKeyException.class) ResponseEntity<ProblemDetail> duplicate() { return problem(409,"CONFLICT"); }
    @ExceptionHandler(io.agenteval.catalog.CatalogConflictException.class) ResponseEntity<ProblemDetail> conflict() { return problem(409,"CONFLICT"); }
    @ExceptionHandler(io.agenteval.experiments.AttemptRejectedException.class) ResponseEntity<ProblemDetail> attempt() { return problem(409,"ATTEMPT_REJECTED"); }
    @ExceptionHandler(io.agenteval.workers.InvalidWorkerCredentialException.class) ResponseEntity<ProblemDetail> credential() { return problem(401,"INVALID_WORKER_CREDENTIAL"); }
    @ExceptionHandler(io.agenteval.workers.CapacityExceededException.class) ResponseEntity<ProblemDetail> capacity() { return problem(429,"CAPACITY_REACHED"); }
    private ResponseEntity<ProblemDetail> problem(int status,String code) {
        var body=ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status),"Request rejected"); body.setProperty("code",code); body.setProperty("request_id",UUID.randomUUID());
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }
}
