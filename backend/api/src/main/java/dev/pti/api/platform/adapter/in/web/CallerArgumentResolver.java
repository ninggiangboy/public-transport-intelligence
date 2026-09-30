package dev.pti.api.platform.adapter.in.web;

import dev.pti.api.platform.domain.Caller;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Lets a controller method declare a {@link Caller} parameter (DOC-49 §5: "lấy Viewer từ security context"). */
public final class CallerArgumentResolver implements HandlerMethodArgumentResolver {

    private final CallerFactory callers;

    public CallerArgumentResolver(CallerFactory callers) {
        this.callers = callers;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return Caller.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            @Nullable ModelAndViewContainer container,
            NativeWebRequest request,
            @Nullable WebDataBinderFactory binderFactory) {
        return callers.from(SecurityContextHolder.getContext().getAuthentication());
    }
}
