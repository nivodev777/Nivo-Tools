
/* Reader screen: sweep PDF (rendered pages) + text documents, zoom,
   jump/bookmarks/recents and typed PDF error reporting. */

package com.nivodev.nivotools.ui.tools.reader;

import android.graphics.Bitmap;
import android.content.Context;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.nivodev.nivotools.R;
import com.nivodev.nivotools.databinding.FragmentReaderBinding;
import com.nivodev.nivotools.util.AnimUtils;
import com.nivodev.nivotools.util.Haptics;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reader mini-app: opens PDF / DOC / DOCX / ODT / TXT / MD / CSV / LOG...
 * PDF pages render lazily on a single worker; everything else becomes soft
 * selectable text inside the same glass panels used across the app.
 */
public class ReaderFragment extends Fragment {

    private static final String PREFS = "reader_store";
    private static final String KEY_FONT = "font_sp";
    private static final int MAX_TEXT_BYTES = 2 * 1024 * 1024;
    private static final int MIN_FONT = 11;
    private static final int MAX_FONT = 30;

    /** Orders in max byte-budget so a hundred pages never eat RAM. */
    private class PagePool {
        private static final int MAX_CACHE_BYTES = 20 * 1024 * 1024;
        private final android.util.LruCache<Integer, Bitmap> stash =
                new android.util.LruCache<Integer, Bitmap>(MAX_CACHE_BYTES) {
                    @Override
                    protected int sizeOf(Integer key, Bitmap value) {
                        return value.getByteCount();
                    }
                };

        Bitmap loadPage(int pos, int fitWidth) {
            PdfRenderer renderer = pdfRenderer;
            if (renderer == null) {
                return null;
            }
            try (PdfRenderer.Page page = renderer.openPage(pos)) {
                int renderH = (int) ((long) page.getHeight() * fitWidth / page.getWidth());
                Bitmap bmp = Bitmap.createBitmap(fitWidth, renderH,
                        Bitmap.Config.ARGB_8888);
                bmp.eraseColor(0xFFF6F6FA);
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                stash.put(pos, bmp);
                return bmp;
            } catch (Throwable t) {
                return null;
            }
        }
    }

    private final ActivityResultLauncher<Uri> treeLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(),
                    uri -> {
                        if (uri != null) {
                            ReaderIndex.addTree(requireContext(), uri);
                            showFileSheet();
                        }
                    });

    /** legacy read permission is session me ek dafa manga - deny loop nahi */
    private boolean askedRead = false;

    private final ActivityResultLauncher<String> legacyReadLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(), granted -> {
                        // grant hua to list refresh: sheet naya khulega
                        // (purana sheet neeche fileSheet field se jalega)
                        if (granted) {
                            showFileSheet();
                        }
                    });

    private final ActivityResultLauncher<String[]> picker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onPicked);

    private FragmentReaderBinding binding;
    private PdfRenderer pdfRenderer;
    private ParcelFileDescriptor pdfFd;
    private PdfPagesAdapter pagesAdapter;
    private PagePool pool;
    private ExecutorService executor;
    private float fontSp = 16f;

    /** PDF zoom factor applied on top of fit-width (0.6x .. 2.6x). */
    private float pdfScale = 1f;
    private android.view.ScaleGestureDetector scaleDetector;
    private boolean pdfMode;
    private Runnable pendingCommit;

    // resume state: last opened file + position restore on every app open
    private Uri activeUri;
    private String activeName;
    private boolean autoResume;
    private int wantScrollY = -1;
    private int wantPage = -1;

    /** Full-screen single page overlay (matrix zoom + pan). */
    private android.app.Dialog fsDialog;
    private FSPageView fsPage;
    /** page currently shown in the full-screen viewer (-1 = none) */
    private int fsPosition = -1;
    /** restore should re-enter the full-screen viewer at the saved page */
    private boolean restoreFs;
    /** true while openKind runs for an AUTO-restore (failures stay quiet) */
    private boolean restoring;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentReaderBinding.inflate(inflater, container, false);
        pool = new PagePool();
        executor = Executors.newSingleThreadExecutor();

        try {
            fontSp = requireContext().getSharedPreferences(PREFS, 0)
                    .getFloat(KEY_FONT, 16f);
        } catch (Throwable t) {
            fontSp = 16f;
        }

        binding.btnReaderOpen.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            Haptics.action(v, Haptics.TYPE_PRESS);
            try {
                picker.launch(new String[]{"*/*"});
            } catch (Throwable t) {
            }
        });
        binding.btnReaderFiles.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            Haptics.action(v, Haptics.TYPE_PRESS);
            showFileSheet();
        });
        binding.btnFontUp.setOnClickListener(v -> changeFont(1));
        binding.btnFontDown.setOnClickListener(v -> changeFont(-1));
        binding.btnFabIn.setOnClickListener(v -> zoomStep(1));
        binding.btnFabOut.setOnClickListener(v -> zoomStep(-1));

        binding.recyclerReaderPages.addOnScrollListener(
                new RecyclerView.OnScrollListener() {
                    @Override
                    public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                        updatePagesPill();
                    }
                });
        binding.readerPagePill.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            Haptics.action(v, Haptics.TYPE_TAP);
            showJumpSheet();
        });

        scaleDetector = new android.view.ScaleGestureDetector(requireContext(),
                new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(android.view.ScaleGestureDetector d) {
                        float f = d.getScaleFactor();
                        if (pdfMode) {
                            applyPdfScale(pdfScale * f, false);
                        } else {
                            overlayFont(fontSp * f);
                        }
                        return true;
                    }

                    @Override
                    public void onScaleEnd(android.view.ScaleGestureDetector d) {
                        commitScale();
                    }
                });
        android.view.View.OnTouchListener pinch = (v, e) -> {
            scaleDetector.onTouchEvent(e);
            if (e.getPointerCount() > 1 || scaleDetector.isInProgress()) {
                // keep the nested scroll / recycler from swallowing the pinch
                v.getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            return false;
        };
        binding.readerText.setOnTouchListener(pinch);
        binding.recyclerReaderPages.setOnTouchListener(pinch);

        AnimUtils.slideFadeIn(binding.getRoot(), 0);
        return binding.getRoot();
    }

    private void onPicked(Uri uri) {
        if (uri == null || binding == null) {
            return;
        }
        // persistable grant so the same file can be reopened after a reboot
        try {
            requireContext().getContentResolver().takePersistableUriPermission(uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable t) {
        }
        autoResume = false;
        wantScrollY = -1;
        wantPage = -1;
        restoreFs = false;
        activeUri = uri;
        activeName = displayName(uri);
        try {
            android.content.SharedPreferences p =
                    requireContext().getSharedPreferences(PREFS, 0);
            boolean pdf = isActivePdf(uri);
            p.edit().putString("last_uri", uri.toString())
                    .putString("last_name", activeName)
                    .putBoolean("last_pdf", pdf)
                    .putInt("last_scroll", 0)
                    .putInt("last_page", 0).apply();
        } catch (Throwable t) {
        }
        openKind(uri);
    }

    private boolean isActivePdf(Uri uri) {
        String lowered = activeName == null ? "" : activeName.toLowerCase();
        try {
            String mime = requireContext().getContentResolver().getType(uri);
            return lowered.endsWith(".pdf") || (mime != null && mime.contains("pdf"));
        } catch (Throwable t) {
            return lowered.endsWith(".pdf");
        }
    }

    private void openKind(Uri uri) {
        if (binding == null || uri == null) {
            return;
        }
        // documents always arrive as content:// (SAF picker, MediaStore or
        // tree walk) - anything else is malformed and gets rejected outright
        if (!"content".equals(uri.getScheme())) {
            if (restoring) {
                openKindFailed();
            } else {
                toastish(getString(R.string.reader_file_err));
            }
            return;
        }
        binding.readerName.setText(activeName != null ? activeName : "document");
        binding.readerTypeHint.setText(docSizeHint(uri));
        boolean wasPdf = isActivePdf(uri);
        String lowered = activeName == null ? "" : activeName.toLowerCase();
        try {
            if (wasPdf) {
                renderPdf(uri);
            } else {
                renderText(uri, lowered);
            }
            addRecent(uri, activeName, wasPdf);
        } catch (java.lang.SecurityException se) {
            // encrypted/password PDFs land here
            if (restoring) {
                openKindFailed();
            } else {
                toastish(getString(R.string.reader_pdf_locked));
            }
        } catch (Throwable t) {
            if (restoring) {
                openKindFailed();
            } else if (wasPdf) {
                toastish(getString(R.string.reader_pdf_bad));
            } else {
                toastish("Could not open this file");
            }
        }
    }

    /** "pdf - 1.2 MB" line for the header chip, or the plain hint when unknown. */
    private String docSizeHint(Uri uri) {
        try {
            android.database.Cursor cur = requireContext().getContentResolver()
                    .query(uri, null, null, null, null);
            if (cur != null) {
                try {
                    int sizeIdx = cur.getColumnIndex(OpenableColumns.SIZE);
                    long bytes = sizeIdx >= 0 && cur.moveToFirst()
                            ? cur.getLong(sizeIdx) : -1L;
                    String kind = isActivePdf(uri) ? "pdf" : "doc";
                    if (bytes > 0) {
                        return kind + " - " + humanSize(bytes);
                    }
                } finally {
                    cur.close();
                }
            }
        } catch (Throwable t) {
        }
        return getString(R.string.reader_type_hint);
    }

    private String humanSize(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
        }
        if (bytes >= 1024L) {
            return String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }

    @Override
    public void onResume() {
        super.onResume();
        tryRestore();
    }

    /** Reopen the last file automatically and jump to its stored position. */
    private void tryRestore() {
        if (binding == null || activeUri != null) {
            return;
        }
        try {
            android.content.SharedPreferences p =
                    requireContext().getSharedPreferences(PREFS, 0);
            String saved = p.getString("last_uri", null);
            if (saved == null || saved.isEmpty()) {
                return;
            }
            Uri uri = Uri.parse(saved);
            activeUri = uri;
            activeName = p.getString("last_name", "document");
            autoResume = true;
            wantScrollY = p.getInt("last_scroll", 0);
            wantPage = p.getInt("last_page", 0);
            restoreFs = p.getBoolean("last_fs", false);
            restoring = true;
            try {
                // the grant already lives from the first open - just try reading
                openKind(uri);
            } catch (Throwable inner) {
                activeUri = null;
                restoring = false;
                openKindFailed();
            } finally {
                restoring = false;
            }
        } catch (Throwable t) {
            activeUri = null;
            wantScrollY = -1;
            wantPage = -1;
            restoreFs = false;
        }
    }

    /**
     * The auto-restored file is no longer readable (moved or deleted).
     * Fail silently: clear the stale "last" entry and offer the file
     * sheet again instead of an error toast every single open.
     */
    private void openKindFailed() {
        try {
            requireContext().getSharedPreferences(PREFS, 0).edit()
                    .putString("last_uri", "")
                    .putBoolean("last_fs", false)
                    .putInt("last_page", 0)
                    .putInt("last_scroll", 0)
                    .apply();
        } catch (Throwable t) {
        }
        activeUri = null;
        try {
            showFileSheet();
        } catch (Throwable t) {
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        try {
            android.content.SharedPreferences.Editor p =
                    requireContext().getSharedPreferences(PREFS, 0).edit();
            if (pdfMode && fsPosition >= 0) {
                // full-screen viewer open - remember its exact page so the
                // reopen lands on the same page (user request)
                p.putInt("last_page", fsPosition);
                p.putBoolean("last_fs", true);
            } else if (!pdfMode && binding != null) {
                p.putInt("last_scroll", binding.readerScroll.getScrollY());
                p.putBoolean("last_fs", false);
            } else if (binding != null) {
                RecyclerView.LayoutManager lm =
                        binding.recyclerReaderPages.getLayoutManager();
                int first = lm instanceof LinearLayoutManager
                        ? ((LinearLayoutManager) lm)
                        .findFirstVisibleItemPosition() : 0;
                p.putInt("last_page", Math.max(first, 0));
                p.putBoolean("last_fs", false);
            }
            p.apply();
        } catch (Throwable t) {
        }
    }

    private String displayName(Uri uri) {
        try {
            android.database.Cursor cursor = requireContext().getContentResolver()
                    .query(uri, null, null, null, null);
            if (cursor != null) {
                try {
                    int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIdx >= 0 && cursor.moveToFirst()) {
                        String got = cursor.getString(nameIdx);
                        if (got != null && !got.isEmpty()) {
                            return got;
                        }
                    }
                } finally {
                    cursor.close();
                }
            }
            String last = uri.getLastPathSegment();
            return last != null && !last.isEmpty() ? last : "document";
        } catch (Throwable t) {
            return "document";
        }
    }

    private void toastish(String msg) {
        com.google.android.material.snackbar.Snackbar.make(
                binding.getRoot(), msg,
                com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show();
    }

    // ------------------------------------------------- device file list sheet

    /** active files-sheet - refresh par purana band karte hain */
    private com.google.android.material.bottomsheet.BottomSheetDialog fileSheet;

    private void showFileSheet() {
        if (binding == null) {
            return;
        }
        try {
            if (fileSheet != null) {
                fileSheet.dismiss();
            }
        } catch (Throwable ignored) {
        }
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(requireContext());
        com.nivodev.nivotools.databinding.SheetReaderFilesBinding fb =
                com.nivodev.nivotools.databinding.SheetReaderFilesBinding
                        .inflate(getLayoutInflater());
        sheet.setContentView(fb.getRoot());
        fb.sheetClose.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            sheet.dismiss();
        });
        fb.btnAddFolder.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            Haptics.action(v, Haptics.TYPE_PRESS);
            try {
                treeLauncher.launch(null);
            } catch (Throwable t) {
            }
        });

        // geka media column: grant par scane, warna EINE permissyo loop
        List<ReaderIndex.Hit> hits = new java.util.ArrayList<>();
        final android.content.Context cx = requireContext();
        final boolean granted = androidx.core.content.ContextCompat.checkSelfPermission(
                cx, "android.permission.READ_EXTERNAL_STORAGE")
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (android.os.Build.VERSION.SDK_INT <= 32 && granted) {
            hits.addAll(ReaderIndex.mediaFiles(cx));
        }
        hits.addAll(ReaderIndex.treeFiles(requireContext()));

        fb.sheetFilesList.removeAllViews();

        // recent documents first - tap to reopen, X button to unlist
        List<JSONArray> recents = recentRows(requireContext());
        if (!recents.isEmpty()) {
            float d = getResources().getDisplayMetrics().density;
            android.widget.TextView rec = new android.widget.TextView(requireContext());
            rec.setText(R.string.reader_recents_title);
            rec.setTextSize(12f);
            rec.setAllCaps(true);
            rec.setAlpha(0.7f);
            rec.setPadding(0, 2, 0, 2);
            rec.setTextColor(
                    androidx.core.content.ContextCompat.getColor(requireContext(),
                            R.color.light_on_surface_variant));
            fb.sheetFilesList.addView(rec);
            for (JSONArray r0 : recents) {
                final String ru = r0.optString(0);
                final String rn = r0.optString(1);
                final boolean rpdf = r0.optBoolean(2, false);
                android.widget.LinearLayout rcard =
                        new android.widget.LinearLayout(requireContext());
                rcard.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                rcard.setGravity(android.view.Gravity.CENTER_VERTICAL);
                rcard.setBackgroundResource(R.drawable.bg_solid_round);
                rcard.setPadding((int) (16f * d), (int) (13f * d),
                        (int) (13f * d), (int) (13f * d));
                android.widget.LinearLayout.LayoutParams rlp =
                        new android.widget.LinearLayout.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.setMargins(0, 0, 0, (int) (7f * d));
                rcard.setLayoutParams(rlp);

                android.widget.TextView rname = new android.widget.TextView(requireContext());
                rname.setText(rn);
                rname.setSingleLine(true);
                rname.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                rname.setTextSize(14f);
                rname.setTextColor(
                        androidx.core.content.ContextCompat.getColor(requireContext(),
                                R.color.light_on_surface));
                android.widget.LinearLayout.LayoutParams rnameLp =
                        new android.widget.LinearLayout.LayoutParams(
                                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                rname.setLayoutParams(rnameLp);
                rcard.addView(rname);

                android.widget.TextView rx = new android.widget.TextView(requireContext());
                rx.setText("\u00d7");
                rx.setTextSize(15f);
                rx.setTextColor(
                        androidx.core.content.ContextCompat.getColor(requireContext(),
                                R.color.light_on_surface_variant));
                rx.setBackgroundResource(R.drawable.bg_circle_chip);
                rx.setPadding((int) (10f * d), (int) (6f * d),
                        (int) (10f * d), (int) (6f * d));
                android.widget.LinearLayout.LayoutParams rxLp =
                        new android.widget.LinearLayout.LayoutParams(
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                rxLp.setMarginStart((int) (10f * d));
                rx.setLayoutParams(rxLp);
                rx.setOnClickListener(v -> {
                    AnimUtils.pressScale(v);
                    Haptics.action(v, Haptics.TYPE_TICK);
                    dropRecent(requireContext(), ru);
                    com.google.android.material.snackbar.Snackbar.make(
                            fb.getRoot(), getString(R.string.reader_recent_removed),
                            com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show();
                    sheet.dismiss();
                    showFileSheet();
                });
                rcard.addView(rx);

                rcard.setOnClickListener(v -> {
                    AnimUtils.pressScale(v);
                    Haptics.action(v, Haptics.TYPE_TAP);
                    try {
                        activeUri = android.net.Uri.parse(ru);
                        activeName = rn;
                        autoResume = false;
                        wantScrollY = -1;
                        wantPage = -1;
                        restoreFs = false;
                        requireContext().getSharedPreferences(PREFS, 0).edit()
                                .putString("last_uri", ru)
                                .putString("last_name", rn)
                                .putBoolean("last_pdf", rpdf)
                                .putInt("last_scroll", 0)
                                .putInt("last_page", 0).apply();
                        openKind(activeUri);
                        sheet.dismiss();
                    } catch (Throwable t) {
                        toastish(getString(R.string.reader_file_err));
                    }
                });
                fb.sheetFilesList.addView(rcard);
            }
        }

        // grant chahiye: pehli baar EK DAFAR system dialog, warna manual
        // "Allow" retry chip (deny par system dialog phir phir nahi ugega)
        if (android.os.Build.VERSION.SDK_INT <= 32 && !granted && !askedRead) {
            askedRead = true;
            try {
                legacyReadLauncher.launch(
                        "android.permission.READ_EXTERNAL_STORAGE");
            } catch (Throwable ignored) {
            }
        }
        // assign global ref: permission refresh me purana sheet jalega
        fileSheet = sheet;
        if (hits.isEmpty()) {
            float d = getResources().getDisplayMetrics().density;
            android.widget.TextView hint = new android.widget.TextView(requireContext());
            hint.setPadding(0, 20, 0, 20);
            hint.setText(R.string.reader_files_empty);
            hint.setTextSize(13f);
            hint.setTextColor(
                    androidx.core.content.ContextCompat.getColor(requireContext(),
                            R.color.light_on_surface_variant));
            fb.sheetFilesList.addView(hint);

            // SDK<=32 par sirf storage grant chahiye - ek retry chip
            if (android.os.Build.VERSION.SDK_INT <= 32 && !granted) {
                android.widget.TextView retry = new android.widget.TextView(requireContext());
                retry.setText(R.string.reader_allow_files);
                retry.setTextSize(14f);
                retry.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                retry.setTextColor(
                        androidx.core.content.ContextCompat.getColor(requireContext(),
                                R.color.light_on_primary));
                retry.setBackgroundResource(R.drawable.bg_ring_btn_off);
                retry.setGravity(android.view.Gravity.CENTER);
                retry.setPadding(0, 0, 0, 0);
                android.widget.LinearLayout.LayoutParams rp =
                        new android.widget.LinearLayout.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                (int) (46f * d));
                rp.topMargin = (int) (6f * d);
                retry.setLayoutParams(rp);
                retry.setOnClickListener(v -> {
                    AnimUtils.pressScale(v);
                    Haptics.action(v, Haptics.TYPE_PRESS);
                    askedRead = false;   // user ne khud dabaya: dobara poochho
                    try {
                        legacyReadLauncher.launch(
                                "android.permission.READ_EXTERNAL_STORAGE");
                    } catch (Throwable ignored) {
                    }
                });
                fb.sheetFilesList.addView(retry);
            }
        } else {
            float d = getResources().getDisplayMetrics().density;
            for (ReaderIndex.Hit hit : hits) {
                // each file lives in its own rounded card
                android.widget.LinearLayout card =
                        new android.widget.LinearLayout(requireContext());
                card.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                card.setGravity(android.view.Gravity.CENTER_VERTICAL);
                card.setBackgroundResource(R.drawable.bg_solid_round);
                card.setPadding((int) (16f * d), (int) (13f * d),
                        (int) (13f * d), (int) (13f * d));
                android.widget.LinearLayout.LayoutParams cardLp =
                        new android.widget.LinearLayout.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                cardLp.setMargins(0, 0, 0, (int) (7f * d));
                card.setLayoutParams(cardLp);

                android.widget.TextView name = new android.widget.TextView(requireContext());
                name.setText(hit.name);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                name.setTextSize(14f);
                name.setTextColor(
                        androidx.core.content.ContextCompat.getColor(requireContext(),
                                R.color.light_on_surface));
                android.widget.LinearLayout.LayoutParams nameLp =
                        new android.widget.LinearLayout.LayoutParams(
                                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                name.setLayoutParams(nameLp);
                card.addView(name);

                String tag = typeTag(hit.name);
                if (tag != null) {
                    android.widget.TextView badge =
                            new android.widget.TextView(requireContext());
                    badge.setText(tag);
                    badge.setTextSize(10f);
                    badge.setAllCaps(true);
                    badge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    badge.setTextColor(
                            androidx.core.content.ContextCompat.getColor(requireContext(),
                                    R.color.light_on_surface_variant));
                    badge.setBackgroundResource(R.drawable.bg_circle_chip);
                    badge.setPadding((int) (8f * d), (int) (5f * d),
                            (int) (8f * d), (int) (5f * d));
                    android.widget.LinearLayout.LayoutParams badgeLp =
                            new android.widget.LinearLayout.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                    badgeLp.setMarginStart((int) (10f * d));
                    badge.setLayoutParams(badgeLp);
                    card.addView(badge);
                }

                card.setOnClickListener(v -> {
                    AnimUtils.pressScale(v);
                    Haptics.action(v, Haptics.TYPE_TAP);
                    try {
                        activeUri = hit.uri;
                        activeName = hit.name;
                        autoResume = false;
                        wantScrollY = -1;
                        wantPage = -1;
                        restoreFs = false;
                        requireContext().getSharedPreferences(PREFS, 0).edit()
                                .putString("last_uri", hit.uri.toString())
                                .putString("last_name", hit.name)
                                .putBoolean("last_pdf", hit.isPdf())
                                .putInt("last_scroll", 0)
                                .putInt("last_page", 0).apply();
                        openKind(hit.uri);
                        sheet.dismiss();
                    } catch (Throwable t) {
                        toastish(getString(R.string.reader_file_err));
                    }
                });
                fb.sheetFilesList.addView(card);
            }
        }
        AnimUtils.slideFadeIn(fb.getRoot(), 0);
        sheet.show();
    }

    /** Short extension badge text, or null when nothing meaningful. */
    private String typeTag(String name) {
        if (name == null) {
            return null;
        }
        String low = name.toLowerCase();
        if (low.endsWith(".pdf")) {
            return "pdf";
        }
        if (low.endsWith(".docx")) {
            return "docx";
        }
        if (low.endsWith(".doc")) {
            return "doc";
        }
        if (low.endsWith(".odt")) {
            return "odt";
        }
        if (low.endsWith(".txt")) {
            return "txt";
        }
        if (low.endsWith(".md")) {
            return "md";
        }
        if (low.endsWith(".csv")) {
            return "csv";
        }
        return "file";
    }

    // ---------------------------------------------------------------- text

    private byte[] readBytes(Uri uri, long cap) throws Exception {
        InputStream in = requireContext().getContentResolver().openInputStream(uri);
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[16384];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > cap) {
                    break;
                }
                bout.write(buf, 0, n);
            }
        } finally {
            try {
                in.close();
            } catch (Throwable t) {
            }
        }
        return bout.toByteArray();
    }

    private void renderText(Uri uri, String lowered) throws Exception {
        closePdf();
        byte[] bytes = readBytes(uri, MAX_TEXT_BYTES);
        String out;
        if (lowered.endsWith(".docx") || lowered.endsWith(".odt")) {
            out = extractZipText(bytes, lowered.endsWith(".odt")
                    ? "content.xml" : "word/document.xml",
                    "<w:t[^>]*>([^<]*)</w:t>");
        } else if (lowered.endsWith(".doc")) {
            out = extractAsciiSlices(bytes);
        } else {
            out = new String(bytes, StandardCharsets.UTF_8);
            if (looksBinary(out)) {
                out = extractAsciiSlices(bytes);
            }
        }
        if (out.trim().isEmpty()) {
            out = getString(R.string.reader_empty);
        }
        showTextMode(out);
        if (wantScrollY >= 0 && binding != null) {
            final int target = wantScrollY;
            wantScrollY = -1;
            binding.readerScroll.postDelayed(() -> {
                if (binding != null) {
                    binding.readerScroll.scrollTo(0, target);
                }
            }, 120L);
        }
    }

    /** DOCX/ODT are zip containers - pull the body xml text runs out. */
    private String extractZipText(byte[] zip, String entryName, String regex) {
        StringBuilder sb = new StringBuilder();
        int entries = 0;
        try (ZipInputStream zin =
                     new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entries++ > 3000) {
                    break;
                }
                String name = entry.getName();
                boolean want = !entry.isDirectory()
                        && (name.equals(entryName) || name.endsWith("/" + entryName));
                if (want) {
                    ByteArrayOutputStream bout = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = zin.read(buf)) > 0) {
                        bout.write(buf, 0, n);
                    }
                    String xml = new String(bout.toByteArray(), StandardCharsets.UTF_8);
                    xml = xml.replace("</w:p>", "\n").replace("</text:p>", "\n")
                            .replace("</text:h>", "\n");
                    Matcher matcher = Pattern.compile(regex).matcher(xml);
                    while (matcher.find()) {
                        sb.append(matcher.group(1)).append(' ');
                    }
                    if (entryName.equals("content.xml")) {
                        Matcher odt = Pattern
                                .compile("<text:p[^>]*>([^<]*)</text:p>")
                                .matcher(xml);
                        sb.setLength(0);
                        // ODT: paragraph tags hold plain runs
                        while (odt.find()) {
                            sb.append(odt.group(1)).append('\n');
                        }
                    }
                }
                zin.closeEntry();
            }
        } catch (Throwable t) {
        }
        return sb.toString()
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
                .replaceAll("[ \\t]{2,}", " ")
                .trim();
    }

    /** Last resort for true .doc binaries: keep runs of readable characters. */
    private String extractAsciiSlices(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            boolean ok = (v >= 0x20 && v <= 0x7E) || v == '\n' || v == '\t'
                    || v == '\r';
            if (ok) {
                run.append((char) v);
            } else {
                if (run.length() >= 5) {
                    out.append(run).append('\n');
                }
                run.setLength(0);
            }
            if (out.length() > MAX_TEXT_BYTES) {
                break;
            }
        }
        if (run.length() >= 5) {
            out.append(run);
        }
        String result = out.toString();
        int start = result.indexOf('\n');
        while (start != -1 && start < result.length()
                && result.indexOf('\n', start + 1) - start <= 2) {
            start++;
        }
        return result.replaceAll("\n{3,}", "\n\n").trim();
    }

    private boolean looksBinary(String s) {
        int noise = 0;
        int limit = Math.min(s.length(), 2048);
        for (int i = 0; i < limit; i++) {
            char c = s.charAt(i);
            if (!Character.isLetterOrDigit(c)
                    && !(c >= 0x20 && c <= 0x7E)
                    && c != '\n' && c != '\t' && c != '\r') {
                noise++;
            }
        }
        return limit > 0 && noise * 100 / limit > 12;
    }

    private void showTextMode(String out) {
        if (binding == null) {
            return;
        }
        pdfMode = false;
        binding.readerEmpty.setVisibility(View.GONE);
        binding.recyclerReaderPages.setVisibility(View.GONE);
        binding.readerPagePill.setVisibility(View.GONE);
        binding.readerText.setVisibility(View.VISIBLE);
        binding.readerFontRow.setVisibility(View.VISIBLE);
        binding.readerFabs.setVisibility(View.VISIBLE);
        binding.readerText.setText(out);
        binding.readerText.setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_SP, fontSp);
    }

    private void changeFont(int move) {
        overlayFont(fontSp + move * 1.5f);
        commitScale();
    }

    /** Live font resize (setTextSize is cheap, applied while gesturing). */
    private void overlayFont(float sp) {
        if (binding == null) {
            return;
        }
        fontSp = Math.max(MIN_FONT, Math.min(MAX_FONT, sp));
        binding.readerText.setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_SP, fontSp);
    }

    private void zoomStep(int sign) {
        if (binding == null) {
            return;
        }
        Haptics.action(requireActivity(),
                sign > 0 ? Haptics.TYPE_TAP : Haptics.TYPE_TICK);
        if (fsPage != null) {
            fsPage.zoomBy(sign > 0 ? 1.2f : 1f / 1.2f);
            return;
        }
        AnimUtils.pressScale(binding.btnFabIn);
        if (pdfMode) {
            applyPdfScale(pdfScale + sign * 0.15f, true);
            commitScale();
        } else {
            overlayFont(fontSp + sign * 1.5f);
            commitScale();
        }
    }

    /** Live PDF zoom preview via view scaling; perspective is re-rendered after. */
    private void applyPdfScale(float newScale, boolean immediate) {
        pdfScale = Math.max(0.6f, Math.min(2.6f, newScale));
        if (binding != null) {
            binding.recyclerReaderPages.setPivotX(
                    binding.recyclerReaderPages.getWidth() / 2f);
            binding.recyclerReaderPages.setPivotY(0f);
            binding.recyclerReaderPages.setScaleX(pdfScale);
            binding.recyclerReaderPages.setScaleY(pdfScale);
        }
        if (immediate) {
            commitScale();
        } else {
            scheduleCommit();
        }
    }

    /** Rerender pages at the new fit width / persist the chosen text size. */
    private void commitScale() {
        cancelCommit();
        if (binding == null) {
            return;
        }
        if (pdfMode) {
            binding.recyclerReaderPages.setScaleX(1f);
            binding.recyclerReaderPages.setScaleY(1f);
            int base = getResources().getDisplayMetrics().widthPixels - 72;
            pagesAdapter = new PdfPagesAdapter(
                    Math.round(base * pdfScale));
            binding.recyclerReaderPages.setAdapter(pagesAdapter);
        } else {
            try {
                requireContext().getSharedPreferences(PREFS, 0).edit()
                        .putFloat(KEY_FONT, fontSp).apply();
            } catch (Throwable t) {
            }
        }
    }

    private void scheduleCommit() {
        cancelCommit();
        if (binding == null) {
            return;
        }
        pendingCommit = () -> commitScale();
        binding.recyclerReaderPages.postDelayed(() -> {
            if (pendingCommit != null) {
                Runnable r = pendingCommit;
                pendingCommit = null;
                r.run();
            }
        }, 450L);
    }

    private void cancelCommit() {
        if (pendingCommit != null) {
            binding.recyclerReaderPages.removeCallbacks(pendingCommit);
            pendingCommit = null;
        }
    }

    // ---------------------------------------------------------------- pdf

    // ------------------------------------------------- pages pill, jump, marks

    /** Floating "N / M" page indicator; tap opens the jump + bookmarks sheet. */
    private void updatePagesPill() {
        if (binding == null || !pdfMode || pdfRenderer == null) {
            return;
        }
        RecyclerView.LayoutManager lm = binding.recyclerReaderPages.getLayoutManager();
        int first = lm instanceof LinearLayoutManager
                ? ((LinearLayoutManager) lm).findFirstVisibleItemPosition() : 0;
        first = Math.max(0, first);
        binding.readerPagePill.setText(getString(R.string.reader_page_of,
                first + 1, pdfRenderer.getPageCount()));
    }

    private int currentPagePos() {
        if (binding == null) {
            return 0;
        }
        RecyclerView.LayoutManager lm = binding.recyclerReaderPages.getLayoutManager();
        return lm instanceof LinearLayoutManager
                ? ((LinearLayoutManager) lm).findFirstVisibleItemPosition() : 0;
    }

    private void scrollToPage(int pos) {
        if (binding == null || pdfRenderer == null || pos < 0
                || pos >= pdfRenderer.getPageCount()) {
            return;
        }
        RecyclerView.LayoutManager lm = binding.recyclerReaderPages.getLayoutManager();
        if (lm instanceof LinearLayoutManager) {
            ((LinearLayoutManager) lm).scrollToPositionWithOffset(pos, 0);
        }
        updatePagesPill();
        Haptics.action(requireActivity(), Haptics.TYPE_CONFIRM);
    }

    /** tap-to-jump + bookmark manager for the open PDF */
    private void showJumpSheet() {
        if (binding == null || pdfRenderer == null || activeUri == null) {
            return;
        }
        final int total = pdfRenderer.getPageCount();
        final String key = activeUri.toString();
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(requireContext());
        com.nivodev.nivotools.databinding.SheetReaderJumpBinding jb =
                com.nivodev.nivotools.databinding.SheetReaderJumpBinding
                        .inflate(getLayoutInflater());
        sheet.setContentView(jb.getRoot());
        // rows are plain LinearLayouts, matching the music sheet patterns
        AnimUtils.slideFadeIn(jb.getRoot(), 0);

        final Runnable[] refreshRows = new Runnable[1];
        refreshRows[0] = () -> {
            jb.bkList.removeAllViews();
            JSONArray marks = bookmarksOf(key);
            float d = getResources().getDisplayMetrics().density;
            if (marks.length() == 0) {
                jb.bkEmpty.setVisibility(View.VISIBLE);
                return;
            }
            jb.bkEmpty.setVisibility(View.GONE);
            for (int i = 0; i < marks.length(); i++) {
                final int page = marks.optInt(i, -1);
                if (page < 1 || page > total) {
                    continue;
                }
                android.widget.LinearLayout row =
                        new android.widget.LinearLayout(requireContext());
                row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setBackgroundResource(R.drawable.bg_solid_round);
                row.setPadding((int) (16f * d), (int) (12f * d),
                        (int) (12f * d), (int) (12f * d));
                android.widget.LinearLayout.LayoutParams rowLp =
                        new android.widget.LinearLayout.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                rowLp.topMargin = (int) (6f * d);
                row.setLayoutParams(rowLp);

                android.widget.TextView pageView =
                        new android.widget.TextView(requireContext());
                pageView.setText(getString(R.string.reader_page_of,
                        page, 0).replace(" / 0", ""));
                pageView.setTextSize(15f);
                pageView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                pageView.setTextColor(
                        androidx.core.content.ContextCompat.getColor(requireContext(),
                                R.color.light_on_surface));
                android.widget.LinearLayout.LayoutParams pageLp =
                        new android.widget.LinearLayout.LayoutParams(
                                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                pageView.setLayoutParams(pageLp);
                row.addView(pageView);

                android.widget.TextView drop = new android.widget.TextView(requireContext());
                drop.setText(R.string.reader_bk_remove);
                drop.setTextSize(12f);
                drop.setTextColor(
                        androidx.core.content.ContextCompat.getColor(requireContext(),
                                R.color.light_on_surface_variant));
                drop.setBackgroundResource(R.drawable.bg_circle_chip);
                drop.setPadding((int) (12f * d), (int) (6f * d),
                        (int) (12f * d), (int) (6f * d));
                drop.setOnClickListener(v -> {
                    AnimUtils.pressScale(v);
                    Haptics.action(v, Haptics.TYPE_TICK);
                    JSONArray left = new JSONArray();
                    for (int j = 0; j < marks.length(); j++) {
                        if (marks.optInt(j, -1) != page) {
                            left.put(marks.optInt(j, -1));
                        }
                    }
                    saveBookmarksOf(key, left);
                    refreshRows[0].run();
                });
                row.addView(drop);

                row.setOnClickListener(v -> {
                    AnimUtils.pressScale(v);
                    scrollToPage(page - 1);
                    sheet.dismiss();
                });
                jb.bkList.addView(row);
            }
        };
        refreshRows[0].run();

        jb.btnJumpGo.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            Haptics.action(v, Haptics.TYPE_CONFIRM);
            try {
                int want = Integer.parseInt(
                        jb.jumpInput.getText().toString().trim());
                if (want >= 1 && want <= total) {
                    scrollToPage(want - 1);
                    sheet.dismiss();
                    return;
                }
                Haptics.action(v, Haptics.TYPE_ERROR);
            } catch (Throwable t) {
                Haptics.action(v, Haptics.TYPE_ERROR);
            }
        });
        jb.btnJumpPrev.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            Haptics.action(v, Haptics.TYPE_TICK);
            int cur = currentPagePos();
            if (cur <= 0) {
                Haptics.action(v, Haptics.TYPE_ERROR);
                return;
            }
            scrollToPage(cur - 1);
            sheet.dismiss();
        });
        jb.btnJumpNext.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            Haptics.action(v, Haptics.TYPE_TICK);
            int cur = currentPagePos();
            if (cur + 1 >= total) {
                Haptics.action(v, Haptics.TYPE_ERROR);
                return;
            }
            scrollToPage(cur + 1);
            sheet.dismiss();
        });
        jb.btnBkAdd.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            Haptics.action(v, Haptics.TYPE_CONFIRM);
            int cur = currentPagePos() + 1;
            JSONArray marks = bookmarksOf(key);
            boolean has = false;
            for (int i = 0; i < marks.length(); i++) {
                if (marks.optInt(i, -1) == cur) {
                    has = true;
                    break;
                }
            }
            if (!has) {
                marks.put(cur);
                saveBookmarksOf(key, marks);
                com.google.android.material.snackbar.Snackbar.make(
                        jb.getRoot(), getString(R.string.reader_bk_added),
                        com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show();
            }
            refreshRows[0].run();
        });

        sheet.show();
    }

    private JSONArray bookmarksOf(String key) {
        try {
            android.content.SharedPreferences p =
                    requireContext().getSharedPreferences(PREFS, 0);
            JSONObject map = new JSONObject(p.getString("bookmarks", "{}"));
            if (map.has(key)) {
                boolean ok = true;
                JSONArray arr = map.optJSONArray(key);
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    if (arr.opt(i) == null || !(arr.opt(i) instanceof Integer)) {
                        ok = false;
                        break;
                    }
                }
                if (ok && arr != null) {
                    return arr;
                }
            }
        } catch (Throwable t) {
        }
        return new JSONArray();
    }

    private void saveBookmarksOf(String key, JSONArray arr) {
        try {
            android.content.SharedPreferences p =
                    requireContext().getSharedPreferences(PREFS, 0);
            JSONObject map = new JSONObject(p.getString("bookmarks", "{}"));
            if (arr.length() == 0) {
                map.remove(key);
            } else {
                map.put(key, arr);
            }
            p.edit().putString("bookmarks", map.toString()).apply();
        } catch (Throwable t) {
        }
    }

    // ------------------------------------------------- recents

    /** remember an opened document (max 8, newest first, dedup by uri). */
    private void addRecent(Uri uri, String name, boolean pdf) {
        if (uri == null) {
            return;
        }
        try {
            android.content.SharedPreferences p =
                    requireContext().getSharedPreferences(PREFS, 0);
            JSONArray arr = new JSONArray(p.getString("recents", "[]"));
            JSONArray out = new JSONArray();
            out.put(uri.toString()).put(name != null ? name : "document")
                    .put(pdf);
            for (int i = 0; i < arr.length() && out.length() < 8 * 3; i += 3) {
                if (uri.toString().equals(arr.optString(i))) {
                    continue;
                }
                JSONArray row = arr.optJSONArray(i);
                if (row != null && row.length() == 3) {
                    out.put(row);
                }
            }
            p.edit().putString("recents", out.toString()).apply();
        } catch (Throwable t) {
        }
    }

    private List<JSONArray> recentRows(Context ctx) {
        List<JSONArray> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(ctx.getSharedPreferences(PREFS, 0)
                    .getString("recents", "[]"));
            for (int i = 0; i + 2 < arr.length(); i += 3) {
                String u = arr.optString(i);
                String n = arr.optString(i + 1);
                boolean pdf = arr.optBoolean(i + 2, false);
                if (u == null || u.isEmpty()) {
                    continue;
                }
                JSONArray row = new JSONArray();
                row.put(u).put(n).put(pdf);
                out.add(row);
            }
        } catch (Throwable t) {
        }
        return out;
    }

    private void dropRecent(Context ctx, String uri) {
        try {
            android.content.SharedPreferences p =
                    ctx.getSharedPreferences(PREFS, 0);
            JSONArray arr = new JSONArray(p.getString("recents", "[]"));
            JSONArray out = new JSONArray();
            for (int i = 0; i + 2 < arr.length(); i += 3) {
                if (uri.equals(arr.optString(i))) {
                    continue;
                }
                JSONArray row = arr.optJSONArray(i);
                if (row != null) {
                    out.put(row);
                } else {
                    String u = arr.optString(i), n = arr.optString(i + 1);
                    JSONArray row2 = new JSONArray();
                    row2.put(u).put(n).put(arr.optBoolean(i + 2, false));
                    out.put(row2);
                }
            }
            p.edit().putString("recents", out.toString()).apply();
        } catch (Throwable t) {
        }
    }

    private void copy(Uri uri, File target, long cap) throws Exception {
        InputStream in = requireContext().getContentResolver().openInputStream(uri);
        FileOutputStream out = new FileOutputStream(target);
        try {
            byte[] buf = new byte[16384];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > cap) {
                    throw new Exception("file too large");
                }
                out.write(buf, 0, n);
            }
        } finally {
            try {
                out.close();
            } catch (Throwable t) {
            }
            try {
                in.close();
            } catch (Throwable t) {
            }
        }
    }

    private void renderPdf(Uri uri) throws Exception {
        closePdf();
        File tmp = new File(requireContext().getCacheDir(), "reader_open.tmp");
        copy(uri, tmp, 64L * 1024 * 1024);
        pdfFd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY);
        pdfRenderer = new PdfRenderer(pdfFd);
        if (pdfRenderer.getPageCount() == 0) {
            throw new java.io.IOException("empty pdf");
        }
        pagesAdapter = new PdfPagesAdapter(
                getResources().getDisplayMetrics().widthPixels - 72);
        binding.recyclerReaderPages.setVisibility(View.VISIBLE);
        binding.recyclerReaderPages.setLayoutManager(
                new LinearLayoutManager(requireContext()));
        binding.recyclerReaderPages.setAdapter(pagesAdapter);
        binding.readerPagePill.setVisibility(View.VISIBLE);
        binding.readerPagePill.post(
                () -> updatePagesPill());
        binding.readerEmpty.setVisibility(View.GONE);
        binding.readerText.setVisibility(View.GONE);
        binding.readerFontRow.setVisibility(View.GONE);
        Haptics.action(requireActivity(), Haptics.TYPE_CONFIRM);
        if (wantPage >= 0 && wantPage < pagesAdapter.getItemCount()) {
            final int target = wantPage;
            wantPage = -1;
            final boolean reopenFs = restoreFs;
            restoreFs = false;
            binding.recyclerReaderPages.postDelayed(() -> {
                if (binding == null || binding.recyclerReaderPages == null) {
                    return;
                }
                RecyclerView.LayoutManager lm =
                        binding.recyclerReaderPages.getLayoutManager();
                if (lm instanceof LinearLayoutManager) {
                    ((LinearLayoutManager) lm).scrollToPositionWithOffset(target, 0);
                }
                if (reopenFs) {
                    // the user left off inside the full page - land there
                    openFullPage(target);
                }
            }, 160L);
        }
    }

    private void closePdf() {
        dismissFullPage();
        try {
            if (pdfRenderer != null) {
                pdfRenderer.close();
            }
        } catch (Throwable t) {
        }
        pdfRenderer = null;
        try {
            if (pdfFd != null) {
                pdfFd.close();
            }
        } catch (Throwable t) {
        }
        pdfFd = null;
        pagesAdapter = null;
        if (pool != null) {
            pool = new PagePool();
        }
        // drop the temp copy only after the renderer/fd are fully closed,
        // so nothing can still be reading it (cache hygiene)
        try {
            if (binding != null) {
                File tmp = new File(requireContext().getCacheDir(),
                        "reader_open.tmp");
                tmp.delete();
            }
        } catch (Throwable t) {
        }
    }

    @Override
    public void onDestroyView() {
        closePdf();
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        binding = null;
        super.onDestroyView();
    }

    // ------------------------------------------------- full-screen single page

    /** Opens one page alone: tap to close, pinch/pan to inspect. */
    private void openFullPage(int position) {
        if (binding == null || pdfRenderer == null) {
            return;
        }
        Haptics.action(requireActivity(), Haptics.TYPE_PRESS);
        fsPosition = position;
        int fit = getResources().getDisplayMetrics().widthPixels;
        // full-screen FS render: poora screen width se maangte hain
        int width = fit - 8;
        Bitmap bmp;
        try {
            bmp = pool.loadPage(position, Math.max(320, width));
        } catch (Throwable t) {
            bmp = null;
        }
        if (bmp == null) {
            toastish(getString(R.string.reader_file_err));
            return;
        }
        dismissFullPage();
        android.widget.FrameLayout root =
                new android.widget.FrameLayout(requireContext());
        fsPage = new FSPageView(requireContext(), bmp);
        root.addView(fsPage, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        int total = pdfRenderer == null ? 0 : pdfRenderer.getPageCount();
        android.widget.TextView tag = new android.widget.TextView(requireContext());
        tag.setText(getString(R.string.reader_page_of, position + 1, total));
        tag.setTextSize(12f);
        tag.setTextColor(ContextCompat.getColor(requireContext(), R.color.light_on_surface));
        tag.setBackgroundResource(R.drawable.bg_solid_round);
        tag.setPadding(24, 10, 24, 10);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
        lp.setMargins(0, 0, 0, 90);
        root.addView(tag, lp);

        android.widget.TextView close = new android.widget.TextView(requireContext());
        close.setText("✕");
        close.setTextSize(15f);
        close.setTextColor(ContextCompat.getColor(requireContext(), R.color.light_on_surface));
        close.setBackgroundResource(R.drawable.bg_solid_round);
        close.setGravity(android.view.Gravity.CENTER);
        close.setOnClickListener(v -> dismissFullPage());
        android.widget.FrameLayout.LayoutParams lpz = new android.widget.FrameLayout.LayoutParams(
                84, 84);
        lpz.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        lpz.setMargins(0, 36, 36, 0);
        root.addView(close, lpz);

        float d = getResources().getDisplayMetrics().density;
        float fabScale = d * 44f;
        float padOut = d * 14f;
        float padIn = 52f * d;
        android.widget.TextView fabIn = new android.widget.TextView(requireContext());
        fabIn.setText("+");
        fabIn.setTextSize(20f);
        fabIn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        fabIn.setTextColor(ContextCompat.getColor(requireContext(), R.color.light_on_primary));
        fabIn.setBackgroundResource(R.drawable.bg_ring_btn_off);
        fabIn.setGravity(android.view.Gravity.CENTER);
        fabIn.setElevation(10f);
        android.widget.FrameLayout.LayoutParams lpi = new android.widget.FrameLayout.LayoutParams(
                (int) fabScale, (int) fabScale);
        lpi.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
        lpi.setMargins(0, 0, (int) padOut, (int) (padOut + fabScale + padIn));
        fabIn.setOnClickListener(v -> {
            fsPage.zoomBy(1.25f);
            Haptics.action(requireActivity(), Haptics.TYPE_TAP);
        });
        root.addView(fabIn, lpi);

        android.widget.TextView fabOut = new android.widget.TextView(requireContext());
        fabOut.setText("−");
        fabOut.setTextSize(20f);
        fabOut.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        fabOut.setTextColor(ContextCompat.getColor(requireContext(), R.color.light_on_surface));
        fabOut.setBackgroundResource(R.drawable.bg_solid_round);
        fabOut.setGravity(android.view.Gravity.CENTER);
        fabOut.setElevation(10f);
        android.widget.FrameLayout.LayoutParams lpo = new android.widget.FrameLayout.LayoutParams(
                (int) fabScale, (int) fabScale);
        lpo.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
        lpo.setMargins(0, 0, (int) padOut, (int) padOut);
        fabOut.setOnClickListener(v -> {
            fsPage.zoomBy(1f / 1.25f);
            Haptics.action(requireActivity(), Haptics.TYPE_TICK);
        });
        root.addView(fabOut, lpo);

        android.app.Dialog dialog = new android.app.Dialog(requireContext());
        dialog.setContentView(root);
        dialog.getWindow().setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        dialog.getWindow().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(0xF0101014));
        dialog.setOnDismissListener(dlg -> {
            fsDialog = null;
            fsPage = null;
        });
        fsDialog = dialog;
        dialog.show();
    }

    private void dismissFullPage() {
        try {
            if (fsDialog != null) {
                fsDialog.dismiss();
            }
        } catch (Throwable t) {
        }
        fsDialog = null;
        fsPage = null;
        fsPosition = -1;   // viewer closed - grid state takes over again
    }

    /** Canvas view: FIRST TIME width-fill auto-fit, user baad me khud zoom/pan */
    private class FSPageView extends android.view.View {
        private final android.graphics.Matrix view = new android.graphics.Matrix();
        private final android.graphics.Paint paper = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final Bitmap bmp;
        private final List<float[]> pointers = new ArrayList<>();
        private float atScale;
        /** width-fit baseline - double tap is par wapas aata hai */
        private float bigScale;
        /** double-tap fareiseiy */
        private long lastUp;
        private float travel;
        /** session me 2 fingers use hui? - pinch ke end tap samjha nahi jayega */
        private boolean wasPinch;
        private boolean sized;

        FSPageView(android.content.Context c, Bitmap bmp) {
            super(c);
            this.bmp = bmp;
            paper.setFilterBitmap(true);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            if (w <= 0 || h <= 0 || sized) {
                return;
            }
            sized = true;
            float bw = bmp.getWidth();
            float bh = bmp.getHeight();
            // AUTO-ADJUST (first time): page ki chaurai == screen ki chaurai
            // (paper full fit ho), vertical center; user ab pinch se khud
            // change kar sakta hai - vo state is change ke baad stay karti hai.
            float s = w / bw;
            atScale = s;
            bigScale = s;
            view.reset();
            view.postScale(s, s, 0, 0);
            view.postTranslate((w - bw * s) / 2f, (h - bh * s) / 2f);
        }

        /** matrix ke translation bounds clamp: page screen se bahar na jaye */
        private void clamp() {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            float[] v = new float[9];
            view.getValues(v);
            float bw = bmp.getWidth() * atScale;
            float bh = bmp.getHeight() * atScale;
            float minTx, maxTx, minTy, maxTy;
            if (bw <= w) {
                minTx = maxTx = (w - bw) / 2f;
            } else {
                maxTx = 0f;
                minTx = w - bw;
            }
            if (bh <= h) {
                minTy = maxTy = (h - bh) / 2f;
            } else {
                maxTy = 0f;
                minTy = h - bh;
            }
            float tx = Math.max(minTx, Math.min(maxTx, v[2]));
            float ty = Math.max(minTy, Math.min(maxTy, v[5]));
            if (tx != v[2] || ty != v[5]) {
                view.postTranslate(tx - v[2], ty - v[5]);
            }
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            canvas.drawColor(0xFF0E0E12);
            canvas.drawBitmap(bmp, view, paper);
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent e) {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN: {
                    travel = 0f;
                    wasPinch = false;
                    pointers.clear();
                    pointers.add(new float[]{e.getX(), e.getY()});
                    return true;
                }
                case android.view.MotionEvent.ACTION_POINTER_DOWN: {
                    int idx = e.getActionIndex();
                    pointers.add(new float[]{e.getX(idx), e.getY(idx)});
                    if (pointers.size() >= 2) {
                        wasPinch = true;
                    }
                    return true;
                }
                case android.view.MotionEvent.ACTION_MOVE: {
                    if (pointers.isEmpty()) {
                        return true;
                    }
                    if (e.getPointerCount() >= 2 && pointers.size() >= 2) {
                        float d0 = distance(pointers.get(0), pointers.get(1));
                        float d1 = distance(
                                new float[]{e.getX(0), e.getY(0)},
                                new float[]{e.getX(1), e.getY(1)});
                        if (d0 > 6f && d1 > 6f) {
                            float f = d1 / d0;
                            if (atScale * f >= 0.5f && atScale * f <= 4f) {
                                view.postScale(f, f, e.getX(0), e.getY(0));
                                atScale *= f;
                            }
                            float dx = (e.getX(0) + e.getX(1)
                                    - pointers.get(0)[0] - pointers.get(1)[0]) / 2f;
                            float dy = (e.getY(0) + e.getY(1)
                                    - pointers.get(0)[1] - pointers.get(1)[1]) / 2f;
                            view.postTranslate(dx, dy);
                        }
                        pointers.set(0, new float[]{e.getX(0), e.getY(0)});
                        pointers.set(1, new float[]{e.getX(1), e.getY(1)});
                    } else if (pointers.size() >= 1) {
                        float dx = e.getX(0) - pointers.get(0)[0];
                        float dy = e.getY(0) - pointers.get(0)[1];
                        travel += Math.abs(dx) + Math.abs(dy);
                        view.postTranslate(dx, dy);
                        pointers.set(0, new float[]{e.getX(0), e.getY(0)});
                    }
                    clamp();
                    invalidate();
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP: {
                    // USER RULE: tap se band NAHI hota (sirf x-dabba par
                    // exit). DOUBLE TAP = barabar 2x zoom, dobara double
                    // tap = wapas fit-width (mine).
                    long now = android.os.SystemClock.uptimeMillis();
                    boolean still = !wasPinch && travel < 60f
                            && e.getPointerCount() <= 1;
                    if (still && now - lastUp < 300L && getWidth() > 0) {
                        float target = atScale >= bigScale * 1.6f
                                ? bigScale      // minimized wapas
                                : bigScale * 2f;   // double size
                        float f = target / atScale;
                        view.postScale(f, f, e.getX(), e.getY());
                        atScale = target;
                        clamp();
                        invalidate();
                        lastUp = 0L;   // teesra tap trail na banaye
                    }
                    lastUp = now;
                    travel = 0f;
                    pointers.clear();
                    return true;
                }
                case android.view.MotionEvent.ACTION_POINTER_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    pointers.clear();
                    return true;
                }
                default:
                    return super.onTouchEvent(e);
            }
        }

        void zoomBy(float f) {
            if (getWidth() <= 0) {
                return;
            }
            if (atScale * f >= 0.5f && atScale * f <= 4f) {
                view.postScale(f, f, getWidth() / 2f, getHeight() / 2f);
                atScale *= f;
                clamp();
                invalidate();
            }
        }

        private float distance(float[] a, float[] b) {
            return (float) Math.hypot(a[0] - b[0], a[1] - b[1]);
        }
    }

    // ------------------------------------------------------- pdf page adapter

    private class PdfPagesAdapter extends RecyclerView.Adapter<PageHolder> {

        private final int fitWidth;

        PdfPagesAdapter(int fitWidth) {
            this.fitWidth = Math.max(320, fitWidth);
        }

        @NonNull
        @Override
        public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_reader_page, parent, false);
            return new PageHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull final PageHolder holder, int position) {
            int total = pdfRenderer == null ? 0 : pdfRenderer.getPageCount();
            holder.label.setText(getString(R.string.reader_page_of,
                    position + 1, Math.max(total, position + 1)));
            holder.img.setImageBitmap(null);
            holder.img.setOnClickListener(v -> openFullPage(position));
            Bitmap hit = pool.stash.get(position);
            if (hit != null && !hit.isRecycled()) {
                holder.img.setImageBitmap(hit);
                holder.img.setMinimumHeight(hit.getHeight());
                return;
            }
            final int pos = position;
            executor.execute(() -> {
                Bitmap rendered = pool.loadPage(pos, fitWidth);
                if (rendered == null || binding == null) {
                    return;
                }
                binding.recyclerReaderPages.post(() -> {
                    if (binding == null) {
                        return;
                    }
                    int at = holder.getAdapterPosition();
                    if (at == RecyclerView.NO_POSITION || at != pos
                            || rendered.isRecycled()) {
                        return;
                    }
                    holder.img.setImageBitmap(rendered);
                    holder.img.setMinimumHeight(rendered.getHeight());
                });
            });
        }

        @Override
        public int getItemCount() {
            return pdfRenderer == null ? 0 : pdfRenderer.getPageCount();
        }
    }

    private static class PageHolder extends RecyclerView.ViewHolder {
        final androidx.appcompat.widget.AppCompatImageView img;
        final android.widget.TextView label;

        PageHolder(View v) {
            super(v);
            img = v.findViewById(R.id.item_page_img);
            label = v.findViewById(R.id.item_page_label);
        }
    }
}
