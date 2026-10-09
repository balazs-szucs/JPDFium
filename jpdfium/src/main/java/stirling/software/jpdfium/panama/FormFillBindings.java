package stirling.software.jpdfium.panama;

import java.lang.foreign.FunctionDescriptor;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * FFM bindings for PDFium interactive form filling ({@code fpdf_formfill.h}), covering the write-side:
 * page lifecycle notifications ({@code FORM_OnAfterLoadPage}, {@code FORM_OnBeforeClosePage}), text field editing ({@code FORM_SetFocusedAnnot}, {@code FORM_SelectAllText}, {@code FORM_ReplaceSelection}), list/combo selection ({@code FORM_SetIndexSelected}), and focus management ({@code FORM_ForceToKillFocus}). The page lifecycle calls are <strong>mandatory</strong>: omitting them causes silent failures where values are not persisted on save.
 */
public final class FormFillBindings {

    private FormFillBindings() {}

    /**
     * EmbedPDF form-model read/write helpers used to clear fields that lost all
     * their widgets (for example when a crop removes an outside widget).
     */
    public static final MethodHandle EPDFForm_LoadModel = downcallOptional("EPDFForm_LoadModel",
            FunctionDescriptor.of(ADDRESS, ADDRESS));

    public static final MethodHandle EPDFForm_CloseModel = downcallOptional("EPDFForm_CloseModel",
            FunctionDescriptor.ofVoid(ADDRESS));

    public static final MethodHandle EPDFForm_CountFields = downcallOptional("EPDFForm_CountFields",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    public static final MethodHandle EPDFForm_CountFieldWidgets =
            downcallOptional("EPDFForm_CountFieldWidgets", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    public static final MethodHandle EPDFForm_GetFieldWidgetObjNum = downcallOptional(
            "EPDFForm_GetFieldWidgetObjNum", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));

    public static final MethodHandle EPDFForm_GetFieldObjNum =
            downcallOptional("EPDFForm_GetFieldObjNum", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

    public static final MethodHandle EPDFForm_ResetField = downcallOptional("EPDFForm_ResetField",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS));

    private static MethodHandle downcall(String name, FunctionDescriptor desc) {
        return Symbols.downcall(name, desc);
    }

    private static MethodHandle downcallCritical(String name, FunctionDescriptor desc) {
        return Symbols.downcallCritical(name, desc);
    }

    private static MethodHandle downcallOptional(String name, FunctionDescriptor desc) {
        return Symbols.downcallOptional(name, desc);
    }

    /**
     * {@code FORM_OnAfterLoadPage(FPDF_PAGE page, FPDF_FORMHANDLE hHandle) -> void}.
     * Must be called immediately after opening a page when a form fill environment is active, notifying the form system the page is live.
     */
    public static final MethodHandle FORM_OnAfterLoadPage = downcall("FORM_OnAfterLoadPage",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

    /**
     * {@code FORM_OnBeforeClosePage(FPDF_PAGE page, FPDF_FORMHANDLE hHandle) -> void}.
     * Must be called immediately before closing a page to let the form system flush pending changes and clean up page-level state.
     */
    public static final MethodHandle FORM_OnBeforeClosePage = downcall("FORM_OnBeforeClosePage",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

    /**
     * {@code FORM_SetFocusedAnnot(FPDF_FORMHANDLE handle, FPDF_ANNOTATION annot) -> FPDF_BOOL}.
     * Programmatically focuses an annotation; for text fields call before {@link #FORM_SelectAllText} and {@link #FORM_ReplaceSelection} to route input to the correct field.
     */
    public static final MethodHandle FORM_SetFocusedAnnot = downcall("FORM_SetFocusedAnnot",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /**
     * {@code FORM_SelectAllText(FPDF_FORMHANDLE hHandle, FPDF_PAGE page) -> FPDF_BOOL}.
     * Selects all text in the currently focused text field; call before {@link #FORM_ReplaceSelection} to replace existing content.
     */
    public static final MethodHandle FORM_SelectAllText = downcall("FORM_SelectAllText",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /**
     * {@code FORM_ReplaceSelection(FPDF_FORMHANDLE hHandle, FPDF_PAGE page, FPDF_WIDESTRING wsText) -> void}.
     * Replaces the current selection with the given UTF-16LE text; used after {@link #FORM_SetFocusedAnnot} and {@link #FORM_SelectAllText} to set a text field value and regenerate its appearance stream.
     */
    public static final MethodHandle FORM_ReplaceSelection = downcall("FORM_ReplaceSelection",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));

    /**
     * {@code FORM_SetIndexSelected(FPDF_FORMHANDLE hHandle, FPDF_PAGE page, int index, FPDF_BOOL selected) -> FPDF_BOOL}.
     * Selects or deselects a list item at the given zero-based index (combo and list boxes); {@code page} must be the raw FPDF_PAGE handle.
     */
    public static final MethodHandle FORM_SetIndexSelected = downcall("FORM_SetIndexSelected",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));

    /**
     * {@code FORM_OnLButtonDown(FPDF_FORMHANDLE hHandle, FPDF_PAGE page, uint32_t modifier, double page_x, double page_y) -> void}.
     * Simulates a left-button press at the given page coordinates; use with {@link #FORM_OnLButtonUp} to toggle checkboxes and select radio buttons.
     */
    public static final MethodHandle FORM_OnLButtonDown = downcall("FORM_OnLButtonDown",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_DOUBLE, JAVA_DOUBLE));

    /**
     * {@code FORM_OnLButtonUp(FPDF_FORMHANDLE hHandle, FPDF_PAGE page, uint32_t modifier, double page_x, double page_y) -> void}.
     * Simulates a left-button release; must follow {@link #FORM_OnLButtonDown} to complete a click on a form widget.
     */
    public static final MethodHandle FORM_OnLButtonUp = downcall("FORM_OnLButtonUp",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_DOUBLE, JAVA_DOUBLE));

    /**
     * {@code FPDF_BOOL FPDF_FFLDraw(FPDF_FORMHANDLE hHandle, FPDF_BITMAP bitmap, FPDF_PAGE page, int start_x, int start_y, int size_x, int size_y, int rotate, int flags)}.
     * Draws the form widgets on top of an already-rendered page bitmap, needed for widgets with absent appearance streams (NeedAppearances) or interactive state the content omits; returns nothing.
     */
    public static final MethodHandle FPDF_FFLDraw = downcall("FPDF_FFLDraw",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS,
                    JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));

    /**
     * {@code FORM_ForceToKillFocus(FPDF_FORMHANDLE hHandle) -> FPDF_BOOL}.
     * Commits the value of the currently focused field and removes focus; call after finishing all fills on a page to ensure changes are flushed.
     */
    public static final MethodHandle FORM_ForceToKillFocus = downcall("FORM_ForceToKillFocus",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
}
