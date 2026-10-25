package cloud.cholewa.appliance.error.processor;

import cloud.cholewa.appliance.error.ApplianceException;
import cloud.cholewa.commons.error.model.ErrorId;
import cloud.cholewa.commons.error.model.ErrorMessage;
import cloud.cholewa.commons.error.model.Errors;
import cloud.cholewa.commons.error.processor.ExceptionProcessor;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;

//Answers a refused request with the status and the code of its cause; the caller's mistake, a WARN, with
//its details answered
@Slf4j
public class ApplianceExceptionProcessor implements ExceptionProcessor {

    @Override
    public Errors apply(final Throwable throwable) {
        final ApplianceException exception = (ApplianceException) throwable;
        final String code = ErrorId.codeOf(exception.getError());

        log.warn("Appliance request refused ({}, {}): {}", exception.getHttpStatus().value(), code, exception.getMessage());

        return Errors.builder()
            .httpStatus(exception.getHttpStatus())
            .errors(Collections.singleton(
                ErrorMessage.builder()
                    .message(exception.getError().getDescription())
                    .details(exception.getMessage())
                    .code(code)
                    .build()
            ))
            .build();
    }
}
