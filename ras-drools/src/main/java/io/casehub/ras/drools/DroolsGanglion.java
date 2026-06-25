package io.casehub.ras.drools;

import io.casehub.ras.api.*;
import io.cloudevents.CloudEvent;
import io.smallrye.mutiny.Uni;
import org.drools.model.codegen.ExecutableModelProject;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.KieModule;
import org.kie.api.builder.Message;
import org.kie.api.builder.Results;
import org.kie.api.conf.EventProcessingOption;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.KieSessionConfiguration;
import org.kie.api.runtime.conf.ClockTypeOption;
import org.kie.api.runtime.rule.FactHandle;
import org.kie.api.time.SessionPseudoClock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class DroolsGanglion implements Ganglion {

    public static final String RESULT_CHANNEL = "results";

    private final DroolsGanglionConfig config;
    private final KieBase kieBase;
    private final DroolsSessionStore sessionStore;
    private final List<DroolsObjectExtractor> extractors;

    public DroolsGanglion(DroolsGanglionConfig config,
                          DroolsSessionStore sessionStore,
                          List<DroolsObjectExtractor> extractors) {
        this.config = config;
        this.sessionStore = sessionStore;
        this.extractors = List.copyOf(extractors);
        this.kieBase = buildKieBase(config);
    }

    @Override
    public String ganglionId() { return config.ganglionId(); }

    @Override
    public Set<String> handledEventTypes() { return config.handledEventTypes(); }

    @Override
    public Uni<DetectionResult> detect(CloudEvent event, SituationContext context) {
        String situationId = context.situationId();
        String correlationKey = context.correlationKey();
        String tenancyId = context.tenancyId();
        boolean isNewSession = false;

        KieSession session;
        if (config.sessionMode() == SessionMode.LONG_LIVED) {
            session = sessionStore.get(config.ganglionId(), situationId, correlationKey, tenancyId)
                    .orElse(null);
            if (session == null) {
                session = createSession();
                isNewSession = true;
            }
        } else {
            session = createSession();
            isNewSession = true;
        }

        var collector = new ResultCollectorChannel();
        session.registerChannel(RESULT_CHANNEL, collector);
        try {
            advanceClock(session, event);
            FactHandle ceHandle = session.insert(event);
            for (var extractor : extractors) {
                for (Object obj : extractor.extract(event)) {
                    session.insert(obj);
                }
            }
            session.fireAllRules();
            session.delete(ceHandle);
        } catch (RuntimeException ex) {
            session.unregisterChannel(RESULT_CHANNEL);
            session.dispose();
            if (!isNewSession) {
                sessionStore.remove(config.ganglionId(), situationId, correlationKey, tenancyId);
            }
            throw ex;
        }

        DetectionResult result = config.resultCollectionStrategy()
                .resolve(collector.results(), config.ganglionId());

        session.unregisterChannel(RESULT_CHANNEL);
        if (config.sessionMode() == SessionMode.LONG_LIVED) {
            sessionStore.put(config.ganglionId(), situationId, correlationKey, tenancyId, session);
        } else {
            session.dispose();
        }

        return Uni.createFrom().item(result);
    }

    @Override
    public Uni<Void> close(String situationId, String correlationKey, String tenancyId) {
        sessionStore.remove(config.ganglionId(), situationId, correlationKey, tenancyId);
        return Uni.createFrom().voidItem();
    }

    private void advanceClock(KieSession session, CloudEvent event) {
        if (config.clockMode() != ClockMode.PSEUDO) {
            return;
        }
        OffsetDateTime eventTime = event.getTime();
        if (eventTime == null) {
            return;
        }
        SessionPseudoClock clock = session.getSessionClock();
        long eventMs = eventTime.toInstant().toEpochMilli();
        long clockMs = clock.getCurrentTime();
        long delta = eventMs - clockMs;
        if (delta < 0) {
            throw new IllegalStateException(
                    "Out-of-order event for ganglion '" + config.ganglionId()
                    + "': event time " + eventMs + " < clock time " + clockMs);
        }
        if (delta > 0) {
            clock.advanceTime(delta, TimeUnit.MILLISECONDS);
        }
    }

    private KieSession createSession() {
        KieSessionConfiguration ksc = KieServices.Factory.get()
                .newKieSessionConfiguration();
        if (config.clockMode() == ClockMode.PSEUDO) {
            ksc.setOption(ClockTypeOption.PSEUDO);
        }
        return kieBase.newKieSession(ksc, null);
    }

    private KieBase buildKieBase(DroolsGanglionConfig config) {
        KieServices ks = KieServices.Factory.get();
        KieFileSystem kfs = ks.newKieFileSystem();
        for (String path : config.classpathRules()) {
            kfs.write(ks.getResources().newClassPathResource(path));
        }
        for (int i = 0; i < config.programmaticRules().size(); i++) {
            kfs.write("src/main/resources/programmatic-" + i + ".drl",
                       config.programmaticRules().get(i));
        }
        KieBuilder kb = ks.newKieBuilder(kfs)
                .buildAll(ExecutableModelProject.class);
        Results results = kb.getResults();
        if (results.hasMessages(Message.Level.ERROR)) {
            throw new IllegalStateException(
                    "DRL compilation failed for ganglion '" + config.ganglionId()
                    + "': " + results.getMessages());
        }
        KieModule module = kb.getKieModule();
        var kbc = ks.newKieBaseConfiguration();
        kbc.setOption(EventProcessingOption.STREAM);
        return ks.newKieContainer(module.getReleaseId()).newKieBase(kbc);
    }
}
