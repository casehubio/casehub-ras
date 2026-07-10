package io.casehub.ras.drools;

import io.casehub.ras.api.*;
import io.cloudevents.CloudEvent;
import io.smallrye.mutiny.Uni;
import org.drools.model.codegen.ExecutableModelProject;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.Message;
import org.kie.api.builder.ReleaseId;
import org.kie.api.builder.Results;
import org.kie.api.conf.EventProcessingOption;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.KieSessionConfiguration;
import org.kie.api.runtime.conf.ClockTypeOption;
import org.kie.api.runtime.rule.FactHandle;
import org.kie.api.time.SessionPseudoClock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class DroolsGanglion implements Ganglion {

    public static final String RESULT_CHANNEL = "results";

    private final String ganglionId;
    private final Set<String> handledEventTypes;
    private final SessionMode sessionMode;
    private final ClockMode clockMode;
    private final ResultCollectionStrategy resultCollectionStrategy;
    private final DroolsSessionStore sessionStore;
    private final List<DroolsObjectExtractor> extractors;
    private volatile KieBase kieBase;
    private volatile long reloadGeneration = 0;
    private ReleaseId currentReleaseId;

    public DroolsGanglion(DroolsGanglionConfig config,
                          DroolsSessionStore sessionStore,
                          List<DroolsObjectExtractor> extractors) {
        this.ganglionId = config.ganglionId();
        this.handledEventTypes = config.handledEventTypes();
        this.sessionMode = config.sessionMode();
        this.clockMode = config.clockMode();
        this.resultCollectionStrategy = config.resultCollectionStrategy();
        this.sessionStore = sessionStore;
        this.extractors = List.copyOf(extractors);
        this.kieBase = buildKieBase(config.classpathRules(), config.programmaticRules());
    }

    @Override
    public String ganglionId() { return ganglionId; }

    @Override
    public Set<String> handledEventTypes() { return handledEventTypes; }

    @Override
    public Uni<DetectionResult> detect(CloudEvent event, SituationContext context) {
        long currentGen = this.reloadGeneration;
        KieBase currentBase = this.kieBase;

        String situationId = context.situationId();
        String correlationKey = context.correlationKey();
        String tenancyId = context.tenancyId();

        KieSession session;
        if (sessionMode == SessionMode.LONG_LIVED) {
            var key = new DroolsSessionKey(ganglionId, situationId, correlationKey, tenancyId);
            try {
                session = sessionStore.computeIfAbsent(key, currentBase, buildSessionConfig(), currentGen);
            } catch (DroolsSessionStoreException ex) {
                try {
                    sessionStore.remove(key);
                } catch (RuntimeException suppressed) {
                    ex.addSuppressed(suppressed);
                }
                throw ex;
            }
        } else {
            session = createSession(currentBase);
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
            if (sessionMode == SessionMode.EPHEMERAL) {
                session.dispose();
            } else {
                sessionStore.remove(new DroolsSessionKey(ganglionId, situationId, correlationKey, tenancyId));
            }
            throw ex;
        }

        DetectionResult result = resultCollectionStrategy
                .resolve(collector.results(), ganglionId);

        session.unregisterChannel(RESULT_CHANNEL);
        if (sessionMode == SessionMode.EPHEMERAL) {
            session.dispose();
        }

        return Uni.createFrom().item(result);
    }

    @Override
    public Uni<Void> close(String situationId, String correlationKey, String tenancyId) {
        sessionStore.remove(new DroolsSessionKey(ganglionId, situationId, correlationKey, tenancyId));
        return Uni.createFrom().voidItem();
    }

    public synchronized void reload(List<String> classpathRules, List<String> programmaticRules) {
        if (classpathRules.isEmpty() && programmaticRules.isEmpty()) {
            throw new IllegalArgumentException("At least one rule source required");
        }
        KieBase newBase = buildKieBase(classpathRules, programmaticRules);
        this.kieBase = newBase;
        this.reloadGeneration++;
    }

    private KieSessionConfiguration buildSessionConfig() {
        KieSessionConfiguration ksc = KieServices.Factory.get().newKieSessionConfiguration();
        if (clockMode == ClockMode.PSEUDO) {
            ksc.setOption(ClockTypeOption.PSEUDO);
        }
        return ksc;
    }

    private KieSession createSession(KieBase base) {
        return base.newKieSession(buildSessionConfig(), null);
    }

    private void advanceClock(KieSession session, CloudEvent event) {
        if (clockMode != ClockMode.PSEUDO) {
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
                    "Out-of-order event for ganglion '" + ganglionId
                    + "': event time " + eventMs + " < clock time " + clockMs);
        }
        if (delta > 0) {
            clock.advanceTime(delta, TimeUnit.MILLISECONDS);
        }
    }

    private KieBase buildKieBase(List<String> classpathRules, List<String> programmaticRules) {
        KieServices ks = KieServices.Factory.get();
        KieFileSystem kfs = ks.newKieFileSystem();
        ReleaseId rid = ks.newReleaseId("io.casehub.ras.drools", ganglionId,
                String.valueOf(System.nanoTime()));
        kfs.generateAndWritePomXML(rid);
        for (String path : classpathRules) {
            kfs.write(ks.getResources().newClassPathResource(path));
        }
        for (int i = 0; i < programmaticRules.size(); i++) {
            kfs.write("src/main/resources/programmatic-" + i + ".drl",
                       programmaticRules.get(i));
        }
        KieBuilder kb = ks.newKieBuilder(kfs)
                .buildAll(ExecutableModelProject.class);
        Results results = kb.getResults();
        if (results.hasMessages(Message.Level.ERROR)) {
            throw new IllegalStateException(
                    "DRL compilation failed for ganglion '" + ganglionId
                    + "': " + results.getMessages());
        }
        var kbc = ks.newKieBaseConfiguration();
        kbc.setOption(EventProcessingOption.STREAM);
        KieBase result = ks.newKieContainer(rid).newKieBase(kbc);

        ReleaseId oldRid = this.currentReleaseId;
        this.currentReleaseId = rid;
        if (oldRid != null) {
            ks.getRepository().removeKieModule(oldRid);
        }
        return result;
    }
}
