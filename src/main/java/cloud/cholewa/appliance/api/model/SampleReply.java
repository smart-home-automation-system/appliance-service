package cloud.cholewa.appliance.api.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

//A valid sample of the draw of a plug, in watts, at the clock of the house - a point of the chart
public record SampleReply(LocalDateTime at, BigDecimal watts) {
}
