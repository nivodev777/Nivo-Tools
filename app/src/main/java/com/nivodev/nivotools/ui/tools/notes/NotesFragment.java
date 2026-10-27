
/* Notes screen: debounced autosave, search, pin, copy/share/delete
   via row options sheet. Never writes partial/filtered stores. */

package com.nivodev.nivotools.ui.tools.notes;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.nivodev.nivotools.R;
import com.nivodev.nivotools.databinding.FragmentNotesBinding;
import com.nivodev.nivotools.databinding.ItemNoteBinding;
import com.nivodev.nivotools.databinding.SheetConfirmBinding;
import com.nivodev.nivotools.databinding.SheetNoteEditBinding;
import com.nivodev.nivotools.databinding.SheetNoteOptionsBinding;
import com.nivodev.nivotools.util.AnimUtils;
import com.nivodev.nivotools.util.Haptics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * Quick-notes tool: glass editor sheet with debounced autosave, search,
 * pinning and a row options sheet (copy / share / duplicate / delete).
 */
public class NotesFragment extends Fragment {

    /** Typing pause before an autosave runs. */
    private static final long SAVE_DELAY_MS = 750L;

    private static final Comparator<JSONObject> PINNED_THEN_UPDATED =
            new Comparator<JSONObject>() {
                @Override
                public int compare(JSONObject a, JSONObject b) {
                    boolean pa = a.optBoolean("pinned", false);
                    boolean pb = b.optBoolean("pinned", false);
                    if (pa != pb) {
                        return pa ? -1 : 1;
                    }
                    return Long.compare(b.optLong("updated", 0L),
                            a.optLong("updated", 0L));
                }
            };

    private FragmentNotesBinding binding;
    private NotesAdapter adapter;
    /** Full store mirror: all notes, sorted by pinned then updated. */
    private List<JSONObject> all = new ArrayList<>();
    /** Display view: subset of {@code all} matching the current search query. */
    private List<JSONObject> items = new ArrayList<>();
    private String query = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentNotesBinding.inflate(inflater, container, false);
        adapter = new NotesAdapter();
        binding.notesRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.notesRecycler.setAdapter(adapter);
        binding.notesRecycler.setHasFixedSize(true);
        AnimUtils.gatePressFeedback(binding.notesRecycler);

        binding.notesSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim();
                load();
            }
        });

        binding.btnNoteAdd.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            Haptics.action(v, Haptics.TYPE_PRESS);
            openEditor(null);
        });

        load();
        return binding.getRoot();
    }

    @Override
    public void onResume() {
        super.onResume();
        load();
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }

    private void load() {
        JSONArray arr = NotesStore.load(requireContext());
        List<JSONObject> rows = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            try {
                rows.add(arr.getJSONObject(i));
            } catch (Throwable t) {
            }
        }
        Collections.sort(rows, PINNED_THEN_UPDATED);
        all = rows;
        items = new ArrayList<>();
        if (!query.isEmpty()) {
            String q = query.toLowerCase();
            for (JSONObject n : all) {
                String hay = (n.optString("title", "") + "\n"
                        + n.optString("body", "")).toLowerCase();
                if (hay.contains(q)) {
                    items.add(n);
                }
            }
        } else {
            items.addAll(all);
        }
        paint();
    }

    private void paint() {
        if (binding == null) {
            return;
        }
        boolean has = !items.isEmpty();
        binding.notesEmpty.setVisibility(has ? View.GONE : View.VISIBLE);
        binding.notesRecycler.setVisibility(has ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    private void persist() {
        JSONArray arr = new JSONArray();
        for (JSONObject o : all) {
            arr.put(o);
        }
        NotesStore.save(requireContext(), arr);
    }

    private int masterIndex(int id) {
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).optInt("id", -1) == id) {
                return i;
            }
        }
        return -1;
    }

    /** Plain text of a note: title on its own line, then the body. */
    private String plainText(JSONObject note) {
        String title = note.optString("title", "").trim();
        String body = note.optString("body", "").trim();
        if (!title.isEmpty() && !body.isEmpty()) {
            return title + "\n" + body;
        }
        return title.isEmpty() ? body : title;
    }

    // ------------------------------------------------------------ editor sheet

    /**
     * Opens the editor sheet. Edits live on a local draft copy first; every
     * typing pause flushes the draft into the list, so dismissing the sheet
     * (back, outside tap or Cancel) can never throw typed text away.
     */
    private void openEditor(final JSONObject existing) {
        final Context cctx = requireContext();
        BottomSheetDialog sheet = new BottomSheetDialog(cctx);
        final SheetNoteEditBinding eb = SheetNoteEditBinding.inflate(getLayoutInflater());
        sheet.setContentView(eb.getRoot());
        AnimUtils.slideFadeIn(eb.getRoot(), 0);

        final Handler h = new Handler(Looper.getMainLooper());
        final JSONObject draft = new JSONObject();
        final int draftId = existing != null ? existing.optInt("id", -1)
                : NotesStore.nextId(cctx);
        final boolean isNew = existing == null;
        final boolean[] added = {existing != null};

        // prefill BEFORE listener attach, so no autosave fires for the copies
        if (existing != null) {
            eb.sheetNoteTitle.setText(existing.optString("title", ""));
            eb.sheetNoteBody.setText(existing.optString("body", ""));
            syncDraft(eb, draft, draftId);
        } else {
            syncDraft(eb, draft, draftId);
        }

        final Runnable flush = () -> flushDraft(eb, draft, draftId, added, isNew, cctx);

        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                syncDraft(eb, draft, draftId);
                h.removeCallbacks(flush);
                h.postDelayed(flush, SAVE_DELAY_MS);
            }
        };
        eb.sheetNoteTitle.addTextChangedListener(watcher);
        eb.sheetNoteBody.addTextChangedListener(watcher);

        eb.sheetCancel.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            Haptics.action(v, Haptics.TYPE_TAP);
            sheet.dismiss();
        });

        eb.sheetSave.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            String title = eb.sheetNoteTitle.getText().toString().trim();
            String body = eb.sheetNoteBody.getText().toString().trim();
            if (title.isEmpty() && body.isEmpty()) {
                Haptics.action(v, Haptics.TYPE_ERROR);
                // brand-new draft left empty: drop its reserved row
                if (isNew && added[0]) {
                    added[0] = false;
                    int idx = masterIndex(draftId);
                    if (idx >= 0) {
                        all.remove(idx);
                    }
                    persist();
                    load();
                }
                return;
            }
            Haptics.action(v, Haptics.TYPE_CONFIRM);
            h.removeCallbacks(flush);
            flushDraft(eb, draft, draftId, added, isNew, cctx);
            sheet.dismiss();
        });

        // back press / outside tap / any dismissal: flush the  last typed text
        sheet.setOnDismissListener(d -> {
            h.removeCallbacks(flush);
            if (binding != null) {
                flushDraft(eb, draft, draftId, added, isNew, cctx);
            }
        });

        sheet.show();
    }

    /** Copies the current widget text into the draft (never into the store). */
    private void syncDraft(SheetNoteEditBinding eb, JSONObject draft, int id) {
        try {
            draft.put("id", id);
            draft.put("title", eb.sheetNoteTitle.getText().toString().trim());
            draft.put("body", eb.sheetNoteBody.getText().toString().trim());
            draft.put("updated", System.currentTimeMillis());
        } catch (Throwable t) {
        }
    }

    /**
     * Single write point for autosave AND manual save. Last writer wins with
     * the newest text, empty new drafts clean themselves up, existing notes
     * with fully cleared text keep their old content.
     */
    private void flushDraft(SheetNoteEditBinding eb, JSONObject draft, int draftId,
                            boolean[] added, boolean isNew, Context cctx) {
        syncDraft(eb, draft, draftId);
        boolean empty = draft.optString("title", "").isEmpty()
                && draft.optString("body", "").isEmpty();
        try {
            if (isNew) {
                if (empty) {
                    if (added[0]) {
                        added[0] = false;
                        int idx = masterIndex(draftId);
                        if (idx >= 0) {
                            all.remove(idx);
                        }
                    }
                } else {
                    int idx = masterIndex(draftId);
                    if (idx >= 0) {
                        all.set(idx, draft);
                    } else {
                        all.add(draft);
                        added[0] = true;
                    }
                }
            } else if (!empty) {
                int idx = masterIndex(draftId);
                if (idx < 0) {
                    all.add(draft);
                } else {
                    all.set(idx, draft);
                }
                added[0] = true;
            }
        } catch (Throwable t) {
        }
        persist();
        if (binding != null) {
            load();
        }
    }

    // ------------------------------------------------------------ options sheet

    private void openOptions(final JSONObject note) {
        final Context cctx = requireContext();
        BottomSheetDialog sheet = new BottomSheetDialog(cctx);
        final SheetNoteOptionsBinding ot = SheetNoteOptionsBinding.inflate(getLayoutInflater());
        sheet.setContentView(ot.getRoot());
        AnimUtils.slideFadeIn(ot.getRoot(), 0);
        final String shared = plainText(note);

        boolean pinned = note.optBoolean("pinned", false);
        ot.sheetOptPin.setText(pinned ? R.string.note_opts_unpin : R.string.note_opts_pin);

        AnimUtils.pressScale(ot.sheetOptPin);
        ot.sheetOptPin.setOnClickListener(v -> {
            Haptics.action(v, Haptics.TYPE_CONFIRM);
            try {
                note.put("pinned", !pinned);
                note.put("updated", note.optLong("updated", System.currentTimeMillis()));
            } catch (Throwable t) {
            }
            persist();
            load();
            sheet.dismiss();
        });

        AnimUtils.pressScale(ot.sheetOptDup);
        ot.sheetOptDup.setOnClickListener(v -> {
            Haptics.action(v, Haptics.TYPE_TICK);
            try {
                JSONObject copy = new JSONObject();
                copy.put("id", NotesStore.nextId(cctx));
                copy.put("title", note.optString("title", "").trim() + " (copy)");
                copy.put("body", note.optString("body", ""));
                copy.put("updated", System.currentTimeMillis());
                int at = masterIndex(note.optInt("id", -1));
                all.add(at >= 0 ? at + 1 : all.size(), copy);
            } catch (Throwable t) {
            }
            persist();
            load();
            sheet.dismiss();
        });

        AnimUtils.pressScale(ot.sheetOptCopy);
        ot.sheetOptCopy.setOnClickListener(v -> {
            if (shared.isEmpty()) {
                Haptics.action(v, Haptics.TYPE_ERROR);
                return;
            }
            Haptics.action(v, Haptics.TYPE_TICK);
            try {
                ClipboardManager cm = (ClipboardManager)
                        cctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("note", shared));
                }
                Toast.makeText(cctx, R.string.note_copied, Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
            }
            sheet.dismiss();
        });

        AnimUtils.pressScale(ot.sheetOptShare);
        ot.sheetOptShare.setOnClickListener(v -> {
            if (shared.isEmpty()) {
                Haptics.action(v, Haptics.TYPE_ERROR);
                return;
            }
            Haptics.action(v, Haptics.TYPE_TICK);
            try {
                cctx.startActivity(Intent.createChooser(
                        new Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, shared), null));
            } catch (Throwable t) {
            }
            sheet.dismiss();
        });

        ot.sheetOptDelete.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            Haptics.action(v, Haptics.TYPE_ERROR);
            sheet.dismiss();
            confirmDelete(note);
        });

        sheet.show();
    }

    private void confirmDelete(final JSONObject note) {
        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        final SheetConfirmBinding cb = SheetConfirmBinding.inflate(getLayoutInflater());
        sheet.setContentView(cb.getRoot());
        AnimUtils.slideFadeIn(cb.getRoot(), 0);
        cb.sheetTitle.setText(R.string.note_delete_title);
        cb.sheetBody.setText(R.string.note_delete_body);
        cb.sheetCancel.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            Haptics.action(v, Haptics.TYPE_TAP);
            sheet.dismiss();
        });
        cb.sheetDanger.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            Haptics.action(v, Haptics.TYPE_ERROR);
            all.remove(note);
            persist();
            load();
            sheet.dismiss();
        });
        sheet.show();
    }

    // ------------------------------------------------------------ adapter

    private class NotesAdapter extends RecyclerView.Adapter<NoteVH> {

        @NonNull
        @Override
        public NoteVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new NoteVH(ItemNoteBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull NoteVH h, int position) {
            final JSONObject note = items.get(position);
            String title = note.optString("title", "");
            h.b.noteTitle.setText(title.isEmpty() ? getString(R.string.menu_notes) : title);
            h.b.noteBody.setText(note.optString("body", ""));
            h.b.noteDate.setText(DateFormat.getDateFormat(requireContext())
                    .format(new Date(note.optLong("updated", 0L))));
            h.b.notePinned.setVisibility(
                    note.optBoolean("pinned", false) ? View.VISIBLE : View.GONE);
            AnimUtils.pressScale(h.b.getRoot());
            h.b.getRoot().setOnClickListener(v -> openEditor(note));
            h.b.getRoot().setOnLongClickListener(v -> {
                Haptics.action(v, Haptics.TYPE_PRESS);
                openOptions(note);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private static class NoteVH extends RecyclerView.ViewHolder {

        final ItemNoteBinding b;

        NoteVH(ItemNoteBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
