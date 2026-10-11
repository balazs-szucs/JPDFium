package stirling.software.jpdfium.fonts.intel;

/**
 * Thrown when a {@link FontRepairPlan} no longer matches the document it is
 * applied to: the targeted font resource moved, disappeared, or its evidence
 * changed after the plan was prepared.
 *
 * <p>This is a refusal, not a document failure: the caller should re-inspect and
 * re-plan against the current state.
 */
public class StaleRepairPlanException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public StaleRepairPlanException(String message) {
        super(message);
    }
}
