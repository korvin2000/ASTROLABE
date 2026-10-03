package io.astrolabe.java;

import io.astrolabe.BalanceProfile;
import io.astrolabe.BalanceProfiles;
import io.astrolabe.Config;
import io.astrolabe.Defaults;
import io.astrolabe.Project;
import io.astrolabe.budget.HeuristicEstimator;
import io.astrolabe.budget.LimitKind;
import io.astrolabe.budget.LimitRule;
import io.astrolabe.budget.LimitSpend;
import io.astrolabe.budget.TaskLimits;
import io.astrolabe.budget.Tokens;
import io.astrolabe.campaign.BudgetStop;
import io.astrolabe.campaign.CampaignOutcome;
import io.astrolabe.campaign.CampaignPolicy;
import io.astrolabe.campaign.LimitHold;
import io.astrolabe.cell.CellModel;
import io.astrolabe.context.ContextArithmetic;
import io.astrolabe.event.AgentEvent;
import io.astrolabe.event.AmendmentProposal;
import io.astrolabe.event.Answer;
import io.astrolabe.event.DClassRequest;
import io.astrolabe.event.Decision;
import io.astrolabe.event.EventRecord;
import io.astrolabe.event.Phase;
import io.astrolabe.event.Question;
import io.astrolabe.event.Resolution;
import io.astrolabe.event.ResolutionOutcome;
import io.astrolabe.event.Subscription;
import io.astrolabe.fixtures.FakeProfiles;
import io.astrolabe.fixtures.TempRepo;
import io.astrolabe.id.AttemptId;
import io.astrolabe.id.Identities;
import io.astrolabe.id.WorkId;
import io.astrolabe.provider.BillableUsage;
import io.astrolabe.provider.Capabilities;
import io.astrolabe.provider.Effort;
import io.astrolabe.provider.Estimate;
import io.astrolabe.provider.InvocationId;
import io.astrolabe.provider.InvocationState;
import io.astrolabe.provider.Item;
import io.astrolabe.provider.JavaInvocation;
import io.astrolabe.provider.JavaProviderAdapter;
import io.astrolabe.provider.Message;
import io.astrolabe.provider.Money;
import io.astrolabe.provider.PriceTable;
import io.astrolabe.provider.Profile;
import io.astrolabe.provider.ProviderAdapters;
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
import io.astrolabe.route.RoutingPacket;
import io.astrolabe.tool.RunArgs;
import io.astrolabe.tool.edit.EditResult;
import io.astrolabe.verify.ReviewRequest;
import io.astrolabe.verify.Verdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
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

    /** Wave A widened these records; the v1.0 full constructors still compile from Java and build the same value. */
    @Test
    void theVersionOneConstructorsStillCompileFromJava() {
        RunArgs run = new RunArgs("run", List.of("git", "status"), null, null, "auto", null, null, false, null, null, null, null, null);
        assertEquals(null, run.getUntilLine());

        EditResult edit = new EditResult(true, "edit-1", Collections.emptyList(), Collections.emptyList(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyList(), Collections.emptyList(), null, null,
                Collections.emptyList(), Collections.emptyMap(), Collections.emptyMap());
        assertTrue(edit.getAuthored().isEmpty());

        ContextArithmetic arithmetic = new ContextArithmetic(BigInteger.valueOf(100), BigInteger.TEN, BigInteger.valueOf(90), BigInteger.ONE, BigInteger.valueOf(11));
        assertEquals(BigInteger.valueOf(11), arithmetic.getWireTokens());
        assertEquals(BigInteger.ZERO, arithmetic.getReserveTokens());

        Defaults d = new Defaults();
        Defaults restored = new Defaults(
                d.getShapePolicy(), d.getTurnsPerCell(), d.getTurnNudgeFraction(),
                d.getProviderTerminalWaitSeconds(), d.getAlpha(), d.getK(), d.getM(), d.getRMaxTokens(),
                d.getAnchorMaxTokens(), d.getImmediateStubTokens(), d.getLookBudgetTokens(),
                d.getRunBudgetTokens(), d.getRegisterCapTokens(), d.getDigestCapTokens(),
                d.getDigestTokensPerRequirement(), d.getDigestCapCeilingTokens(), d.getPatchCapTokens(),
                d.getFactLineMaxChars(), d.getNoteBodyMaxTokens(), d.getNoteSummaryMaxChars(),
                d.getSeedsMaxTokens(), d.getInjectionMaxNotes(), d.getInjectionMaxTokens(),
                d.getFocusNotesMaxTokens(), d.getFocusZoomMaxTokens(), d.getTouchedInAnchor(),
                d.getCheckerTimeBoxSeconds(), d.getCheckerFallbackTimeBoxSeconds(), d.getTheta(),
                d.getFullSuiteCadence(), d.getReserveVerification(), d.getReserveRecoveryAndPersist(),
                d.getCampaignRecoveryReserve(), d.getStallTurns(), d.getLoopIdentical(),
                d.getRepeatedSignatureRepairs(), d.getDoomLoopSameCalls(), d.getProbeTurns(), d.getProbeTokens(),
                d.getProbeTier(), d.getReviewLookMax(), d.getReviewIncrementTokens(), d.getReviewCampaignTokens(),
                d.getReviewTier(), d.getReviewRoutineTier(), d.getRepairCalls(), d.getAttemptsPerIncrement(),
                d.getWriterDepth(), d.getProbeDepth(), d.getParallelCells(), d.getCampaignCells(),
                d.getFlakyIsolatedReruns(), d.getAdmissionConfidenceMax(), d.getProfileRoles(), d.getMode(),
                d.getExecutionMode(), d.getDClass(), d.getIntegrityApproval(), d.getUnknownOutcomeReconciliation(),
                d.getCeiling(), d.getRunTimeoutSeconds(), d.getGitDeadlineSeconds());
        assertEquals(d, restored);

        Identities ids = new Identities(new WorkId("W-1"), new AttemptId("a1"), null, null);
        AgentEvent.Cell.ModelResponded responded = new AgentEvent.Cell.ModelResponded(ids, "inv-1", StopReason.EndTurn, null, Phase.Understand, null, null);
        assertEquals(null, responded.getFacts());
        Response response = new Response(Collections.emptyList(), StopReason.EndTurn, null, null);
        assertEquals(null, response.getFacts());
        BillableUsage usage = new BillableUsage(Collections.emptyMap(), new UsageProvenance("java", "m", "java/1"), Collections.emptySet(), null, null, BillableUsage.SCHEMA_VERSION);
        assertEquals(null, usage.getBilled());
        Profile profile = FakeProfiles.INSTANCE.getMain();
        PriceTable prices = profile.getPriceTable();
        assertEquals(prices, new PriceTable(prices.getDate(), prices.getCurrency(), prices.getPerMillion()));
        Request request = new Request(Collections.emptyList(), Collections.emptyList(), profile, Effort.Medium, 1_000, null, null);
        assertEquals(null, request.getSessionKey());
        RoutingPacket packet = new RoutingPacket(null, 1_000L, 100, null, null, null);
        assertEquals(0L, packet.getReserveTokens());
        // C3: the policy's v1 constructors stay; limits and the balance profile are plain Java values.
        CampaignPolicy v1 = new CampaignPolicy(Tokens.of(1_000), null, false, Collections.emptyList());
        assertEquals(null, v1.getLimits());
        assertEquals(null, v1.getBalance());
        Config config = new Config(d, Collections.emptyMap(), d.getProfileRoles(), d.getMode(), d.getExecutionMode(), d.getDClass(),
                d.getIntegrityApproval(), d.getUnknownOutcomeReconciliation(), d.getCeiling(), null, new io.astrolabe.auth.RedactionConfig(),
                null, new io.astrolabe.Flags(), Collections.emptyMap(), Collections.emptyList(), io.astrolabe.route.TierTable.UNTIERED, true);
        assertEquals(BalanceProfile.Balanced, config.getBalance());
    }

    @Test
    void aJavaHostSetsTaskLimitsAndAProfileAtStart() {
        TaskLimits limits = new TaskLimits(new Money("USD", new BigDecimal("50"), false), 480, 3_000);
        CampaignPolicy policy = new CampaignPolicy(Tokens.of(1_000_000), null, false, Collections.emptyList(), limits, BalanceProfile.Economy);
        assertEquals(480, policy.getLimits().getMaxMinutes());
        assertEquals(BalanceProfile.Economy, policy.getBalance());
        assertEquals(3, LimitRule.reserveRequests(3_000));
        TaskLimits requestsOnly = new TaskLimits(null, null, 100);
        assertTrue(requestsOnly.getAny());
        assertEquals(BalanceProfile.Thorough, new Config().withBalance(BalanceProfile.Thorough).getBalance());
        assertTrue(BalanceProfiles.slowdown(BalanceProfiles.vector(BalanceProfile.Economy)).getWorst() <= BalanceProfiles.SOFT_SLOWDOWN);
    }

    @Test
    void aJavaHostReadsTheHoldingLimitAndSetsAnExplicitEffort() {
        // C14: the holding limit is a plain value with wire words; an explicit effort is a constructor argument.
        TaskLimits limits = new TaskLimits(null, null, 100);
        LimitHold hold = new LimitHold(BudgetStop.TaskLimitRequests, LimitRule.status(limits, LimitSpend.of(Collections.emptyList(), 0L, "USD")), "task limit: 100 of 100 model requests spent");
        assertEquals(LimitKind.Requests, hold.getLimit());
        assertEquals("task_limit_requests", hold.getStop().getWire());
        assertEquals(null, BudgetStop.ContractBudget.getLimit());
        assertTrue(BudgetStop.ContractBudget.getResumable());
        Profile profile = FakeProfiles.INSTANCE.getMain();
        CellModel model = new CellModel(ProviderAdapters.fromJava(new ScriptedJavaAdapter(Collections.emptyList())), profile, new HeuristicEstimator(),
                Effort.High, profile.getCapabilities().getOutputLimitTokens(), false, true);
        assertEquals(Effort.High, BalanceProfiles.effort(model, BalanceProfiles.vector(BalanceProfile.Economy)));
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
