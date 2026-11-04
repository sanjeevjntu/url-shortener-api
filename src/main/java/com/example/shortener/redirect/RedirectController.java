package com.example.shortener.redirect;

import com.example.shortener.analytics.ClickEvent;
import com.example.shortener.analytics.ClickRecorder;
import com.example.shortener.common.error.ApiException;
import com.example.shortener.common.error.ErrorCode;
import com.example.shortener.link.Link;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Clock;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/// `GET /{code}`: the public redirect.
///
/// The path regex matches the code format, so `/actuator/...`, `/swagger-ui.html` and other routes are never
/// captured. `302` (not `301`) so browsers do not cache the hop and every click is counted (decision D4).
@RestController
public class RedirectController {

    private final RedirectService redirects;
    private final ClickRecorder clicks;
    private final Clock clock;

    public RedirectController(RedirectService redirects, ClickRecorder clicks, Clock clock) {
        this.redirects = redirects;
        this.clicks = clicks;
        this.clock = clock;
    }

    @Operation(summary = "Redirect to the target URL")
    @ApiResponse(responseCode = "302", description = "Found; Location is the target URL")
    @ApiResponse(responseCode = "404", description = "Unknown code")
    @ApiResponse(responseCode = "410", description = "Expired or deleted")
    @ApiResponse(responseCode = "429", description = "Rate limited; see Retry-After")
    @GetMapping("/{code:" + Link.CODE_PATTERN + "}")
    public ResponseEntity<Void> redirect(@PathVariable String code, HttpServletRequest request) {
        return switch (redirects.resolve(code)) {
            case Resolution.Redirect(var linkCode, var targetUrl) -> {
                // Spring also routes HEAD here. Link checkers and chat previews send HEAD: not a visit, not counted.
                if (HttpMethod.GET.matches(request.getMethod())) {
                    // Non-blocking hand-off: analytics can never slow down or fail the redirect.
                    clicks.record(ClickEvent.of(
                            linkCode,
                            clock.instant(),
                            request.getHeader(HttpHeaders.REFERER),
                            request.getHeader(HttpHeaders.USER_AGENT)));
                }
                yield ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create(targetUrl))
                        .cacheControl(CacheControl.noStore())
                        .build();
            }
            case Resolution.Gone _ ->
                throw new ApiException(ErrorCode.LINK_GONE, "Link '" + code + "' is no longer available");
            case Resolution.NotFound _ ->
                throw new ApiException(ErrorCode.LINK_NOT_FOUND, "No link with code '" + code + "'");
        };
    }
}
