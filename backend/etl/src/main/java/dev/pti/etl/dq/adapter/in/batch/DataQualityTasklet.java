package dev.pti.etl.dq.adapter.in.batch;

import dev.pti.etl.dq.application.RunDataQualityChecks;
import dev.pti.etl.dq.domain.PostWriteRule;
import java.util.List;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

/**
 * Step {@code runDueRules} of {@code DataQualityJob} (DOC-16 §3): maps the step context to the use case, one rule per
 * call ({@code CONTINUABLE}) so no transaction is long.
 */
public class DataQualityTasklet implements Tasklet {

    static final String NEXT = "pti.dq.nextRule";
    static final String RAN = "pti.dq.ran";

    private final RunDataQualityChecks checks;

    public DataQualityTasklet(RunDataQualityChecks checks) {
        this.checks = checks;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        ExecutionContext context = contribution.getStepExecution().getExecutionContext();
        List<PostWriteRule> rules = List.of(PostWriteRule.values());
        int index = context.getInt(NEXT, 0);
        if (index >= rules.size()) {
            contribution.setExitStatus(
                    ExitStatus.COMPLETED.addExitDescription("Ran " + context.getString(RAN, "no rule")));
            return RepeatStatus.FINISHED;
        }
        context.putInt(NEXT, index + 1);
        PostWriteRule rule = rules.get(index);
        if (checks.runIfDue(rule)) {
            String ran = context.getString(RAN, "");
            context.putString(RAN, ran.isEmpty() ? rule.id() : ran + ", " + rule.id());
        }
        return RepeatStatus.CONTINUABLE;
    }
}
