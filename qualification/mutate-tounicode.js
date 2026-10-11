// Controlled font-metadata mutation for the paired recovery lane.
//
// Deletes /ToUnicode from every font dictionary reachable from the trailer.
// Deterministic and derived only from the pristine original: no external
// corpus, no randomness. Run with:
//
//   mutool run qualification/mutate-tounicode.js <input.pdf> <output.pdf>
//
// Prints "font_dicts=<N> tounicode_dropped=<M>" on stdout. When M == 0 the
// input declared no /ToUnicode anywhere and the case is not_applicable to the
// recovery lane (recorded, never scored as a recovery).
//
// NB: PDFObject.forEach passes the *value* first (callback(value, key)); arrays
// likewise pass the element first.
var input = scriptArgs[0];
var output = scriptArgs[1];
var doc = Document.openDocument(input);
var visited = {};
var dropped = 0;
var fonts = 0;

function walk(ref) {
    if (!ref || ref.isNull()) return;
    if (ref.isIndirect()) {
        var num = ref.asIndirect();
        if (visited[num]) return;
        visited[num] = true;
        ref = ref.resolve();
        if (!ref) return;
    }
    if (ref.isDictionary()) {
        var t = ref.get("Type");
        if (t && t.isName() && t.asName() === "Font") {
            fonts++;
            var tu = ref.get("ToUnicode");
            if (tu && !tu.isNull()) {
                ref.delete("ToUnicode");
                dropped++;
            }
        }
        ref.forEach(function (val) { walk(val); });
    } else if (ref.isArray()) {
        ref.forEach(function (val) { walk(val); });
    }
}

walk(doc.getTrailer());
doc.save(output);
print("font_dicts=" + fonts + " tounicode_dropped=" + dropped);
