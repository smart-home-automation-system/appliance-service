package cloud.cholewa.appliance.api;

import cloud.cholewa.appliance.api.model.ApplianceReply;
import cloud.cholewa.appliance.api.model.RunReply;
import cloud.cholewa.appliance.api.model.SampleReply;
import cloud.cholewa.appliance.service.ApplianceService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

//The read API of the appliances (HAS-278), under the base path /home/appliance; routed by the gateway with
//HAS-281. Reads only - the acknowledgement is HAS-279's. Takes the request and hands it to ApplianceService
@RestController
@RequiredArgsConstructor
public class ApplianceController {

    //a local date-time without an offset (2026-10-24T00:00:00), read by the clock of the house - the rules
    //of the other histories. A pattern instead of ISO.DATE_TIME, which takes an offset as well and silently
    //drops it; an offset or a zone is a 400
    static final String LOCAL_DATE_TIME = "yyyy-MM-dd'T'HH:mm:ss";

    private final ApplianceService applianceService;

    @GetMapping
    Mono<List<ApplianceReply>> appliances() {
        return applianceService.appliances();
    }

    @GetMapping("{id}")
    Mono<ApplianceReply> appliance(@PathVariable final long id) {
        return applianceService.appliance(id);
    }

    @GetMapping("{id}/runs")
    Flux<RunReply> runs(
        @PathVariable final long id,
        @RequestParam @DateTimeFormat(pattern = LOCAL_DATE_TIME) final LocalDateTime from,
        @RequestParam @DateTimeFormat(pattern = LOCAL_DATE_TIME) final LocalDateTime to
    ) {
        return applianceService.runs(id, from, to);
    }

    @GetMapping("{id}/samples")
    Flux<SampleReply> samples(
        @PathVariable final long id,
        @RequestParam @DateTimeFormat(pattern = LOCAL_DATE_TIME) final LocalDateTime from,
        @RequestParam @DateTimeFormat(pattern = LOCAL_DATE_TIME) final LocalDateTime to
    ) {
        return applianceService.samples(id, from, to);
    }
}
