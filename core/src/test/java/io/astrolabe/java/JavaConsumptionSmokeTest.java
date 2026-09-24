package io.astrolabe.java;

import io.astrolabe.Config;
import io.astrolabe.Project;
import io.astrolabe.campaign.CampaignOutcome;
import io.astrolabe.event.AgentEvent;
import io.astrolabe.event.AmendmentProposal;
import io.astrolabe.event.Answer;
import io.astrolabe.event.DClassRequest;
import io.astrolabe.event.Decision;
import io.astrolabe.event.EventRecord;
import io.astrolabe.event.Question;
import io.astrolabe.event.Resolution;
import io.astrolabe.event.ResolutionOutcome;
import io.astrolabe.event.Subscription;
import io.astrolabe.fixtures.FakeProfiles;
import io.astrolabe.fixtures.TempRepo;
import io.astrolabe.provider.BillableUsage;
import io.astrolabe.provider.Capabilities;
import io.astrolabe.provider.Estimate;
import io.astrolabe.provider.InvocationId;
import io.astrolabe.provider.InvocationState;
import io.astrolabe.provider.Item;
import io.astrolabe.provider.JavaInvocation;
import io.astrolabe.provider.JavaProviderAdapter;
import io.astrolabe.provider.Message;
import io.astrolabe.provider.Profile;
import io.astrolabe.provider.Request;
import io.astrolabe.provider.Response;
import io.astrolabe.provider.Role;
import io.astrolabe.provider.StopReason;
import io.astrolabe.provider.Terminal;
import io.astrolabe.provider.ToolCall;
import io.astrolabe.provider.UsageNormalizer;
import io.astrolabe.provider.UsageProvenance;
import io.astrolabe.provider.Validation;
import io.astrolabe.provider.Validations;
import io.astrolabe.verify.ReviewRequest;
import io.astrolabe.verify.Verdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1.12.3 (IX-14): a Java host drives the SDK through {@link AstrolabeJava} only, with a Java-authored provider
 * adapter and authority — open a project, run an S0 campaign that asks two questions (one answered, one refused),
 * subscribe an event sink, read views and outcomes, cancel a second campaign — and sees no coroutine types.
 */
class JavaConsumptionSmokeTest {
    @TempDir
    Path stateRoot;

    /** Replies in order; once they run out, a call hangs until it is cancelled. */
    static final class ScriptedJavaAdapter implements JavaProviderAdapter {
        private final Deque<List<Item>> replies;

        ScriptedJavaAdapter(List<List<Item>> replies) {
            this.replies = new ArrayDeque<>(replies);
        }

        @Override
        public String id() {
            return "java-scripted";
        }

        @Override
        public Capabilities capabilities(Profile profile) {
            return profile.getCapabilities();
        }

        @Override
        public Validation validate(Request request, Estimate estimate) {
            return Validations.standard(request, estimate, request.getProfile().getCapabilities());
        }

        @Override
        public JavaInvocation start(Request request, InvocationId id) {
            List<Item> items;
            synchronized (replies) {
                items = replies.poll();
            }
            CompletableFuture<Response> response = new CompletableFuture<>();
            if (items != null) {
                response.complete(new Response(items, hasCall(items) ? StopReason.ToolUse : StopReason.EndTurn, null, null));
            }
            return new JavaInvocation() {
                @Override
                public InvocationId id() {
                    return id;
                }

                @Override
                public InvocationState state() {
                    return response.isDone() ? InvocationState.TerminalReconciled : InvocationState.Requested;
                }

                @Override
                public CompletableFuture<Response> response() {
                    return response;
                }

                @Override
                public void cancel() {
                    response.cancel(false);
                }

                @Override
                public CompletableFuture<Terminal> terminal() {
                    return response.handle((r, failure) -> new Terminal(id, r, null, Collections.emptyList(), null, failure != null));
                }
            };
        }

        @Override
        public UsageNormalizer normalizer() {
            return (nativeUsage, profile) -> BillableUsage.missing(new UsageProvenance("java", profile.getModel(), "java/1"), FakeProfiles.INSTANCE.getDimensions(), nativeUsage);
        }

        private static boolean hasCall(List<Item> items) {
            for (Item item : items) {
                if (item instanceof ToolCall) return true;
            }
            return false;
        }
    }

    /** Answers the first question, refuses every later one, denies effects, leaves reviews unanswered. */
    static final class OneAnswerAuthority implements JavaAuthority {
        final AtomicInteger asked = new AtomicInteger();

        @Override
        public CompletableFuture<Answer> ask(Question question) {
            if (asked.incrementAndGet() == 1) {
                return CompletableFuture.completedFuture(new Answer(question.getId(), question.getContractRevision(), "return 10", null, false));
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Decision> approve(DClassRequest request) {
            return CompletableFuture.completedFuture(new Decision(request.getId(), request.getContractRevision(), false, "not in this smoke"));
        }

        @Override
        public CompletableFuture<Resolution> resolve(AmendmentProposal proposal) {
            return CompletableFuture.completedFuture(new Resolution(proposal.getId(), proposal.getContractRevision(), ResolutionOutcome.Rejected, "java-host", "not in this smoke"));
        }

        @Override
        public CompletableFuture<Verdict> review(ReviewRequest request) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static List<Item> ask(String id, String question) {
        return List.of(Message.text(Role.Assistant, "asking"), new ToolCall(id, "task", "{\"op\":\"ask\",\"question\":\"" + question + "\"}", null));
    }

    @Test
    void aJavaHostRunsAskReadsAndCancelsThroughAstrolabeJavaOnly() throws Exception {
        try (TempRepo repo = TempRepo.Companion.create(null)) {
            repo.write("Makefile", "test:\n\techo ok\n");
            repo.write("src/a.py", "def a():\n    return 1\n");
            repo.commit("initial");
            Config config = new Config().withStateRoot(stateRoot.toString()).withProfiles(FakeProfiles.INSTANCE.getAll());
            ScriptedJavaAdapter adapter = new ScriptedJavaAdapter(List.of(ask("c1", "which value should a return?"), ask("c2", "may I also change b?")));
            OneAnswerAuthority authority = new OneAnswerAuthority();
            List<EventRecord> seen = new CopyOnWriteArrayList<>();

            try (AstrolabeJava sdk = new AstrolabeJava(config, adapter, authority);
                 Project project = sdk.open(repo.getRoot());
                 Subscription all = sdk.subscribe(seen::add)) {
                JavaCampaignHandle first = sdk.campaignBlocking(project, "make a return 10");
                CampaignOutcome outcome = first.await().get(60, TimeUnit.SECONDS);
                assertEquals(CampaignOutcome.WaitingForInput, outcome, "the refused question ends the campaign waiting for input");
                assertEquals(2, authority.asked.get());
                assertEquals(1, first.views().contract(first.workId()).getContracts().size());
                assertTrue(first.isDone());

                JavaCampaignHandle second = sdk.campaign(project, "make a return 11").get(30, TimeUnit.SECONDS);
                CompletableFuture<CampaignOutcome> pending = second.await();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (seen.stream().noneMatch(r -> r.getEvent() instanceof AgentEvent.Cell.ModelRequested && r.getEvent().getIds().getWork().equals(second.workId()))) {
                    assertTrue(System.nanoTime() < deadline, "the second campaign reached the model");
                    Thread.sleep(10);
                }
                second.cancel();
                assertEquals(CampaignOutcome.Cancelled, pending.get(30, TimeUnit.SECONDS));
                assertTrue(all.getActive());
            }
            assertTrue(seen.stream().anyMatch(r -> r.getEvent() instanceof AgentEvent.Ask.Answered));
        }
    }

    @Test
    void theJavaFacadeExposesNoCoroutineTypes() {
        for (Class<?> type : List.of(AstrolabeJava.class, JavaCampaignHandle.class, JavaAuthority.class, Project.class)) {
            for (Method method : type.getMethods()) {
                if (method.isSynthetic()) continue;
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertFalse(parameter.getName().startsWith("kotlin.coroutines"), type.getSimpleName() + "." + method.getName() + " takes a continuation");
                }
                assertFalse(method.getReturnType().getName().startsWith("kotlinx.coroutines"), type.getSimpleName() + "." + method.getName() + " returns a coroutine type");
            }
        }
    }
}
