package cloud.cholewa.appliance.api;

import cloud.cholewa.appliance.api.model.MemberReportReply;
import cloud.cholewa.appliance.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

//The chores report (HAS-345) under /home/appliance/reports, routed by the /appliance/** route of the gateway. The
//range is local date-times of the house, as the other reads (ApplianceController.LOCAL_DATE_TIME); its rules and the
//report are ReportService's
@RestController
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    //the cycles that finished in [from, to), at most 366 days - 400 INVALID_RANGE otherwise
    @GetMapping("reports/members")
    Mono<MemberReportReply> members(
        @RequestParam @DateTimeFormat(pattern = ApplianceController.LOCAL_DATE_TIME) final LocalDateTime from,
        @RequestParam @DateTimeFormat(pattern = ApplianceController.LOCAL_DATE_TIME) final LocalDateTime to
    ) {
        return reportService.members(from, to);
    }
}
