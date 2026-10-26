package io.agenteval.evaluation;
import io.agenteval.security.CurrentActor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.*;
@RestController
@RequestMapping("/api/v1/results/{resultId}/reviews")
public class HumanReviewController {
    private final HumanReviewService reviews;
    private final CurrentActor current;
    public HumanReviewController(HumanReviewService reviews,CurrentActor current) { this.reviews=reviews; this.current=current; }
    public record Review(BigDecimal score,String reason) {}
    @PostMapping public HumanReview add(Authentication auth,@PathVariable UUID resultId,@RequestBody Review command) { return reviews.add(current.require(auth),resultId,command.score(),command.reason()); }
    @GetMapping public List<HumanReview> list(Authentication auth,@PathVariable UUID resultId,@RequestParam(defaultValue="50") int limit) { return reviews.list(current.require(auth),resultId,limit); }
}
