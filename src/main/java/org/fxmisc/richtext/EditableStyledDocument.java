package org.fxmisc.richtext;

import static org.fxmisc.richtext.ReadOnlyStyledDocument.ParagraphsPolicy.*;
import static org.fxmisc.richtext.TwoDimensional.Bias.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import org.fxmisc.richtext.ReadOnlyStyledDocument.ParagraphsPolicy;
import org.reactfx.EventSource;
import org.reactfx.EventStream;
import org.reactfx.EventStreams;
import org.reactfx.Guard;
import org.reactfx.util.Lists;
import org.reactfx.value.SuspendableVar;
import org.reactfx.value.Val;
import org.reactfx.value.Var;

/**
 * Content model for {@link StyledTextArea}. Implements edit operations
 * on styled text, but not worrying about additional aspects such as
 * caret or selection.
 */
final class EditableStyledDocument<S, PS> extends StyledDocumentBase<S, PS, ObservableList<Paragraph<S, PS>>> {

    /* ********************************************************************** *
     *                                                                        *
     * Observables                                                            *
     *                                                                        *
     * Observables are "dynamic" (i.e. changing) characteristics of an object.*
     * They are not directly settable by the client code, but change in       *
     * response to user input and/or API actions.                             *
     *                                                                        *
     * ********************************************************************** */

    /**
     * Content of this {@code StyledDocument}.
     */
    private final StringBinding text = Bindings.createStringBinding(() -> getText(0, length()));
    @Override
    public String getText() { return text.getValue(); }
    public ObservableValue<String> textProperty() { return text; }

    /**
     * Length of this {@code StyledDocument}.
     */
    private final SuspendableVar<Integer> length = Var.newSimpleVar(0).suspendable();
    public int getLength() { return length.getValue(); }
    public Val<Integer> lengthProperty() { return length; }
    @Override
    public int length() { return length.getValue(); }

    /**
     * Unmodifiable observable list of styled paragraphs of this document.
     */
    @Override
    public ObservableList<Paragraph<S, PS>> getParagraphs() {
        return FXCollections.unmodifiableObservableList(paragraphs);
    }

    /**
     * Read-only snapshot of the current state of this document.
     */
    public ReadOnlyStyledDocument<S, PS> snapshot() {
        return new ReadOnlyStyledDocument<>(paragraphs, ParagraphsPolicy.COPY);
    }


    /* ********************************************************************** *
     *                                                                        *
     * Event streams                                                          *
     *                                                                        *
     * ********************************************************************** */

    // To publish a text change:
    //   1. push to textChangePosition,
    //   2. push to textRemovalEnd,
    //   3. push to insertedText.
    //
    // To publish a rich change:
    //   a)
    //     1. push to textChangePosition,
    //     2. push to textRemovalEnd,
    //     3. push to insertedDocument;
    //   b)
    //     1. push to styleChangePosition
    //     2. push to styleChangeEnd
    //     3. push to styleChangeDone.

    private final EventSource<Integer> textChangePosition = new EventSource<>();
    private final EventSource<Integer> styleChangePosition = new EventSource<>();

    private final EventSource<Integer> textRemovalEnd = new EventSource<>();
    private final EventSource<Integer> styleChangeEnd = new EventSource<>();

    private final EventSource<String> insertedText = new EventSource<>();

    private final EventSource<StyledDocument<S, PS>> insertedDocument = new EventSource<>();
    private final EventSource<Void> styleChangeDone = new EventSource<>();

    private final EventStream<PlainTextChange> plainTextChanges;
    public EventStream<PlainTextChange> plainTextChanges() { return plainTextChanges; }

    private final EventStream<RichTextChange<S, PS>> richChanges;
    public EventStream<RichTextChange<S, PS>> richChanges() { return richChanges; }

    {
        EventStream<String> removedText = EventStreams.zip(textChangePosition, textRemovalEnd).map(t2 -> t2.map((a, b) -> getText(a, b)));
        EventStream<Integer> changePosition = EventStreams.merge(textChangePosition, styleChangePosition);
        EventStream<Integer> removalEnd = EventStreams.merge(textRemovalEnd, styleChangeEnd);
        EventStream<StyledDocument<S, PS>> removedDocument = EventStreams.zip(changePosition, removalEnd).map(t2 -> t2.map((a, b) -> subSequence(a, b)));
        EventStream<Integer> insertionEnd = styleChangeEnd.emitOn(styleChangeDone);
        EventStream<StyledDocument<S, PS>> insertedDocument = EventStreams.merge(
                this.insertedDocument,
                changePosition.emitBothOnEach(insertionEnd).map(t2 -> t2.map((a, b) -> subSequence(a, b))));

        plainTextChanges = EventStreams.zip(textChangePosition, removedText, insertedText)
                .filter(t3 -> t3.map((pos, removed, inserted) -> !removed.equals(inserted)))
                .map(t3 -> t3.map((pos, removed, inserted) -> new PlainTextChange(pos, removed, inserted)));

        richChanges = EventStreams.zip(changePosition, removedDocument, insertedDocument)
                .filter(t3 -> t3.map((pos, removed, inserted) -> !removed.equals(inserted)))
                .map(t3 -> t3.map((pos, removed, inserted) -> new RichTextChange<>(pos, removed, inserted)));
    }


    /* ********************************************************************** *
     *                                                                        *
     * Properties                                                             *
     *                                                                        *
     * ********************************************************************** */

    final BooleanProperty useInitialStyleForInsertion = new SimpleBooleanProperty();


    /* ********************************************************************** *
     *                                                                        *
     * Fields                                                                 *
     *                                                                        *
     * ********************************************************************** */

    private final S initialStyle;

    private final PS initialParagraphStyle;


    /* ********************************************************************** *
     *                                                                        *
     * Constructors                                                           *
     *                                                                        *
     * ********************************************************************** */

    @SuppressWarnings("unchecked")
    EditableStyledDocument(S initialStyle, PS initialParagraphStyle) {
        super(FXCollections.observableArrayList(new Paragraph<>(initialParagraphStyle, "", initialStyle)));
        this.initialStyle = initialStyle;
        this.initialParagraphStyle = initialParagraphStyle;
    }


    /* ********************************************************************** *
     *                                                                        *
     * Actions                                                                *
     *                                                                        *
     * Actions change the state of the object. They typically cause a change  *
     * of one or more observables and/or produce an event.                    *
     *                                                                        *
     * ********************************************************************** */

    public void replaceText(int start, int end, String text) {
        StyledDocument<S, PS> doc = ReadOnlyStyledDocument.fromString(
                text, getStyleForInsertionAt(start), getParagraphStyleForInsertionAt(start));
        replace(start, end, doc);
    }

    public void replace(int start, int end, StyledDocument<S, PS> replacement) {
        ensureValidRange(start, end);

        textChangePosition.push(start);
        textRemovalEnd.push(end);

        Position start2D = navigator.offsetToPosition(start, Forward);
        Position end2D = start2D.offsetBy(end - start, Forward);
        int firstParIdx = start2D.getMajor();
        int firstParFrom = start2D.getMinor();
        int lastParIdx = end2D.getMajor();
        int lastParTo = end2D.getMinor();

        // Get the leftovers after cutting out the deletion
        Paragraph<S, PS> firstPar = paragraphs.get(firstParIdx).trim(firstParFrom);
        Paragraph<S, PS> lastPar = paragraphs.get(lastParIdx).subSequence(lastParTo);

        List<Paragraph<S, PS>> replacementPars = replacement.getParagraphs();

        List<Paragraph<S, PS>> newPars = join(firstPar, replacementPars, lastPar);
        setAll(firstParIdx, lastParIdx + 1, newPars);

        // update length, invalidate text
        int replacementLength =
                replacementPars.stream().mapToInt(Paragraph::length).sum() +
                replacementPars.size() - 1;
        int newLength = length.getValue() - (end - start) + replacementLength;
        length.suspendWhile(() -> { // don't publish length change until text is invalidated
            length.setValue(newLength);
            text.invalidate();
        });

        // complete the change events
        insertedText.push(replacement.toString());
        StyledDocument<S, PS> doc =
                replacement instanceof ReadOnlyStyledDocument
                ? replacement
                : new ReadOnlyStyledDocument<>(replacement.getParagraphs(), COPY);
        insertedDocument.push(doc);
    }

    public void setStyle(int from, int to, S style) {
        ensureValidRange(from, to);

        try(Guard commitOnClose = beginStyleChange(from, to)) {
            Position start = navigator.offsetToPosition(from, Forward);
            Position end = to == from
                    ? start
                    : start.offsetBy(to - from, Backward);
            int firstParIdx = start.getMajor();
            int firstParFrom = start.getMinor();
            int lastParIdx = end.getMajor();
            int lastParTo = end.getMinor();

            if(firstParIdx == lastParIdx) {
                Paragraph<S, PS> p = paragraphs.get(firstParIdx);
                p = p.restyle(firstParFrom, lastParTo, style);
                paragraphs.set(firstParIdx, p);
            } else {
                int affectedPars = lastParIdx - firstParIdx + 1;
                List<Paragraph<S, PS>> restyledPars = new ArrayList<>(affectedPars);

                Paragraph<S, PS> firstPar = paragraphs.get(firstParIdx);
                restyledPars.add(firstPar.restyle(firstParFrom, firstPar.length(), style));

                for(int i = firstParIdx + 1; i < lastParIdx; ++i) {
                    Paragraph<S, PS> p = paragraphs.get(i);
                    restyledPars.add(p.restyle(style));
                }

                Paragraph<S, PS> lastPar = paragraphs.get(lastParIdx);
                restyledPars.add(lastPar.restyle(0, lastParTo, style));

                setAll(firstParIdx, lastParIdx + 1, restyledPars);
            }
        }
    }

    public void setStyle(int paragraph, S style) {
        Paragraph<S, PS> p = paragraphs.get(paragraph);
        int start = position(paragraph, 0).toOffset();
        int end = start + p.length();

        try(Guard commitOnClose = beginStyleChange(start, end)) {
            p = p.restyle(style);
            paragraphs.set(paragraph, p);
        }
    }

    public void setStyle(int paragraph, int fromCol, int toCol, S style) {
        ensureValidParagraphRange(paragraph, fromCol, toCol);
        int parOffset = position(paragraph, 0).toOffset();
        int start = parOffset + fromCol;
        int end = parOffset + toCol;

        try(Guard commitOnClose = beginStyleChange(start, end)) {
            Paragraph<S, PS> p = paragraphs.get(paragraph);
            p = p.restyle(fromCol, toCol, style);
            paragraphs.set(paragraph, p);
        }
    }

    public void setStyleSpans(int from, StyleSpans<? extends S> styleSpans) {
        int len = styleSpans.length();
        ensureValidRange(from, from + len);

        Position start = offsetToPosition(from, Forward);
        Position end = start.offsetBy(len, Backward);
        int skip = terminatorLengthToSkip(start);
        int trim = terminatorLengthToTrim(end);
        if(skip + trim >= len) {
            return;
        } else if(skip + trim > 0) {
            styleSpans = styleSpans.subView(skip, len - trim);
            len -= skip + trim;
            from += skip;
            start = start.offsetBy(skip, Forward);
            end = end.offsetBy(-trim, Backward);
        }

        try(Guard commitOnClose = beginStyleChange(from, from + len)) {
            int firstParIdx = start.getMajor();
            int firstParFrom = start.getMinor();
            int lastParIdx = end.getMajor();
            int lastParTo = end.getMinor();

            if(firstParIdx == lastParIdx) {
                Paragraph<S, PS> p = paragraphs.get(firstParIdx);
                Paragraph<S, PS> q = p.restyle(firstParFrom, styleSpans);
                if(q != p) {
                    paragraphs.set(firstParIdx, q);
                }
            } else {
                Paragraph<S, PS> firstPar = paragraphs.get(firstParIdx);
                Position spansFrom = styleSpans.position(0, 0);
                Position spansTo = spansFrom.offsetBy(firstPar.length() - firstParFrom, Backward);
                Paragraph<S, PS> q = firstPar.restyle(firstParFrom, styleSpans.subView(spansFrom, spansTo));
                if(q != firstPar) {
                    paragraphs.set(firstParIdx, q);
                }
                spansFrom = spansTo.offsetBy(1, Forward); // skip the newline

                for(int i = firstParIdx + 1; i < lastParIdx; ++i) {
                    Paragraph<S, PS> par = paragraphs.get(i);
                    spansTo = spansFrom.offsetBy(par.length(), Backward);
                    q = par.restyle(0, styleSpans.subView(spansFrom, spansTo));
                    if(q != par) {
                        paragraphs.set(i, q);
                    }
                    spansFrom = spansTo.offsetBy(1, Forward); // skip the newline
                }

                Paragraph<S, PS> lastPar = paragraphs.get(lastParIdx);
                spansTo = spansFrom.offsetBy(lastParTo, Backward);
                q = lastPar.restyle(0, styleSpans.subView(spansFrom, spansTo));
                if(q != lastPar) {
                    paragraphs.set(lastParIdx, q);
                }
            }
        }
    }

    public void setStyleSpans(int paragraph, int from, StyleSpans<? extends S> styleSpans) {
        int len = styleSpans.length();
        ensureValidParagraphRange(paragraph, from, len);
        int parOffset = position(paragraph, 0).toOffset();
        int start = parOffset + from;
        int end = start + len;

        try(Guard commitOnClose = beginStyleChange(start, end)) {
            Paragraph<S, PS> p = paragraphs.get(paragraph);
            Paragraph<S, PS> q = p.restyle(from, styleSpans);
            if(q != p) {
                paragraphs.set(paragraph, q);
            }
        }
    }

    public void setParagraphStyle(int parIdx, PS style) {
        ensureValidParagraphIndex(parIdx);
        Paragraph<S, PS> par = paragraphs.get(parIdx);
        int len = par.length();
        int start = position(parIdx, 0).toOffset();
        int end = start + len;

        try(Guard commitOnClose = beginStyleChange(start, end)) {
            Paragraph<S, PS> q = par.setParagraphStyle(style);
            paragraphs.set(parIdx, q);
        }
    }


    /* ********************************************************************** *
     *                                                                        *
     * Private and package private methods                                    *
     *                                                                        *
     * ********************************************************************** */

    private void ensureValidParagraphIndex(int parIdx) {
        Lists.checkIndex(parIdx, paragraphs.size());
    }

    private void ensureValidRange(int start, int end) {
        Lists.checkRange(start, end, length());
    }

    private void ensureValidParagraphRange(int par, int start, int end) {
        ensureValidParagraphIndex(par);
        Lists.checkRange(start, end, fullLength(par));
    }

    private int fullLength(int par) {
        int n = paragraphs.size();
        return paragraphs.get(par).length() + (par == n-1 ? 0 : 1);
    }

    private int terminatorLengthToSkip(Position pos) {
        Paragraph<S, PS> par = paragraphs.get(pos.getMajor());
        int skipSum = 0;
        while(pos.getMinor() == par.length() && pos.getMajor() < paragraphs.size() - 1) {
            skipSum += 1;
            pos = pos.offsetBy(1, Forward); // will jump to the next paragraph
            par = paragraphs.get(pos.getMajor());
        }
        return skipSum;
    }

    private int terminatorLengthToTrim(Position pos) {
        int parLen = paragraphs.get(pos.getMajor()).length();
        int trimSum = 0;
        while(pos.getMinor() > parLen) {
            assert pos.getMinor() - parLen == 1;
            trimSum += 1;
            pos = pos.offsetBy(-1, Backward); // may jump to the end of previous paragraph, if parLen was 0
            parLen = paragraphs.get(pos.getMajor()).length();
        }
        return trimSum;
    }

    private Guard beginStyleChange(int start, int end) {
        styleChangePosition.push(start);
        styleChangeEnd.push(end);
        return () -> styleChangeDone.push(null);
    }

    private List<Paragraph<S, PS>> join(Paragraph<S, PS> first, List<Paragraph<S, PS>> middle, Paragraph<S, PS> last) {
        int m = middle.size();
        if(m == 0) {
            return Arrays.asList(first.concat(last));
        } else if(m == 1) {
            return Arrays.asList(first.concat(middle.get(0)).concat(last));
        } else {
            List<Paragraph<S, PS>> res = new ArrayList<>(middle.size());
            res.add(first.concat(middle.get(0)));
            res.addAll(middle.subList(1, m - 1));
            res.add(middle.get(m-1).concat(last));
            return res;
        }
    }

    // TODO: Replace with ObservableList.setAll(from, to, col) when implemented.
    // See https://javafx-jira.kenai.com/browse/RT-32655.
    private void setAll(int startIdx, int endIdx, Collection<Paragraph<S, PS>> pars) {
        if(startIdx > 0 || endIdx < paragraphs.size()) {
            paragraphs.subList(startIdx, endIdx).clear(); // note that paragraphs remains non-empty at all times
            paragraphs.addAll(startIdx, pars);
        } else {
            paragraphs.setAll(pars);
        }
    }

    S getStyleForInsertionAt(int pos) {
        return getStyleForInsertionAt(navigator.offsetToPosition(pos, Forward));
    }

    S getStyleForInsertionAt(Position insertionPos) {
        if(useInitialStyleForInsertion.get()) {
            return initialStyle;
        } else {
            Paragraph<S, PS> par = paragraphs.get(insertionPos.getMajor());
            return par.getStyleAtPosition(insertionPos.getMinor());
        }
    }

    PS getParagraphStyleForInsertionAt(int pos) {
        return getParagraphStyleForInsertionAt(navigator.offsetToPosition(pos, Forward));
    }

    PS getParagraphStyleForInsertionAt(Position insertionPos) {
        if(useInitialStyleForInsertion.get()) {
            return initialParagraphStyle;
        } else {
            Paragraph<S, PS> par = paragraphs.get(insertionPos.getMajor());
            return par.getParagraphStyle();
        }
    }
}
