package cloud.cholewa.appliance.api;

import cloud.cholewa.appliance.api.model.ClaimReply;
import cloud.cholewa.appliance.api.model.ClaimRequest;
import cloud.cholewa.appliance.service.ClaimService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

//The claims of a chore with the NFC tag on an appliance (HAS-343), posted by the tag page of the dashboard
//(HAS-346). Takes the request and hands it to ClaimService
@RestController
@RequiredArgsConstructor
public class ClaimController {

    private final ClaimService claimService;

    //200 with the claim; 400 INVALID_TAG / UNKNOWN_MEMBER, 404 NOT_FOUND_APPLIANCE (the appliance of the tag is not
    //watched), 409 REPLAYED_TAG / NOTHING_TO_CLAIM, 503 when the household registry cannot be read
    @PostMapping("claims")
    Mono<ClaimReply> claim(@Valid @RequestBody final ClaimRequest request) {
        return claimService.claim(request);
    }
}
