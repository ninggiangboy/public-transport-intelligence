package dev.pti.api.platform.adapter.in.web;

import dev.pti.api.platform.domain.ApiException.FieldError;
import dev.pti.api.platform.domain.Caller;
import dev.pti.api.platform.domain.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.BeanUtils;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Rejects a query parameter that the handler does not declare (DOC-31 §9, DOC-32 §1): a client that types
 * {@code routeID} must get a 400, not unfiltered data. The declared names come from the handler method: {@code
 * @RequestParam} and unannotated simple parameters by name, and the properties of a model object (a record or bean
 * parameter, annotated {@code @ModelAttribute} or not). A handler with a {@code @RequestParam Map} takes everything.
 *
 * <p>Runs as an MVC interceptor, after authentication, authorization and rate limiting, so an unknown parameter never
 * tells an unauthorized caller anything.
 */
public final class UnknownQueryParameterInterceptor implements HandlerInterceptor {

    private static final ParameterNameDiscoverer NAMES = new DefaultParameterNameDiscoverer();

    private final Map<Method, Set<String>> declared = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        Set<String> allowed = declared.computeIfAbsent(method.getMethod(), m -> declaredNames(method));
        if (allowed.contains("*")) {
            return true;
        }
        List<FieldError> errors = new ArrayList<>();
        for (String name : new TreeSet<>(request.getParameterMap().keySet())) {
            if (!allowed.contains(name)) {
                errors.add(new FieldError(name, "is not a known parameter"));
            }
        }
        if (!errors.isEmpty()) {
            throw new ValidationException("The request has parameters that this endpoint does not accept.", errors);
        }
        return true;
    }

    static Set<String> declaredNames(HandlerMethod handlerMethod) {
        Set<String> names = new HashSet<>();
        for (MethodParameter parameter : handlerMethod.getMethodParameters()) {
            parameter.initParameterNameDiscovery(NAMES);
            RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
            if (requestParam != null) {
                if (Map.class.isAssignableFrom(parameter.getParameterType())) {
                    names.add("*");
                } else {
                    names.add(nameOf(requestParam, parameter));
                }
            } else if (parameter.hasParameterAnnotation(ModelAttribute.class) || isModelObject(parameter)) {
                names.addAll(propertiesOf(parameter.getParameterType()));
            } else if (isSimple(parameter)) {
                String name = parameter.getParameterName();
                if (name != null) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private static String nameOf(RequestParam annotation, MethodParameter parameter) {
        if (!annotation.name().isEmpty()) {
            return annotation.name();
        }
        if (!annotation.value().isEmpty()) {
            return annotation.value();
        }
        String name = parameter.getParameterName();
        return name != null ? name : "";
    }

    private static boolean isSimple(MethodParameter parameter) {
        return !hasOtherBinding(parameter)
                && BeanUtils.isSimpleProperty(parameter.nestedIfOptional().getNestedParameterType());
    }

    private static boolean isModelObject(MethodParameter parameter) {
        Class<?> type = parameter.getParameterType();
        if (hasOtherBinding(parameter)
                || BeanUtils.isSimpleProperty(parameter.nestedIfOptional().getNestedParameterType())) {
            return false;
        }
        String name = type.getName();
        return !type.isPrimitive()
                && !name.startsWith("java.")
                && !name.startsWith("jakarta.")
                && !name.startsWith("org.springframework.")
                && !type.equals(Caller.class);
    }

    private static boolean hasOtherBinding(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(PathVariable.class)
                || parameter.hasParameterAnnotation(RequestBody.class)
                || parameter.hasParameterAnnotation(RequestHeader.class)
                || parameter.hasParameterAnnotation(RequestPart.class);
    }

    private static Set<String> propertiesOf(Class<?> type) {
        Set<String> names = new HashSet<>();
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                names.add(component.getName());
            }
        } else {
            for (PropertyDescriptor descriptor : BeanUtils.getPropertyDescriptors(type)) {
                if (descriptor.getWriteMethod() != null) {
                    names.add(descriptor.getName());
                }
            }
        }
        return names;
    }
}
