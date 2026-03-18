package io.autocrypt.jwlee.cowork.core.hitl;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Provides static access to the Spring ApplicationEventPublisher.
 * This is necessary because Embabel State objects are not managed by Spring's DI
 * and their action parameters must come from the Blackboard.
 */
@Component
public class ApplicationContextHolder implements ApplicationContextAware {

    private static ApplicationContext context;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        context = applicationContext;
    }

    public static ApplicationEventPublisher getPublisher() {
        return context;
    }
}
