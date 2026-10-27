
/* Calculator screen: pad, expression preview sign and history sheet.
   Math evaluation lives in CalcEngine; persistence in Prefs. */

package com.nivodev.nivotools.ui.tools.calculator;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.nivodev.nivotools.databinding.FragmentCalculatorBinding;
import com.nivodev.nivotools.util.AnimUtils;

public class CalculatorFragment extends Fragment {

    private FragmentCalculatorBinding binding;
    private static final java.util.List<String[]> HISTORY = new java.util.ArrayList<>();

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentCalculatorBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        bind(binding.btnC, "C");
        bind(binding.btnParen, "( )");
        bind(binding.btnPercent, "%");
        bind(binding.btnDiv, "÷");
        bind(binding.btnMul, "×");
        bind(binding.btnSub, "−");
        bind(binding.btnAdd, "+");
        bind(binding.btn7, "7");
        bind(binding.btn8, "8");
        bind(binding.btn9, "9");
        bind(binding.btn4, "4");
        bind(binding.btn5, "5");
        bind(binding.btn6, "6");
        bind(binding.btn1, "1");
        bind(binding.btn2, "2");
        bind(binding.btn3, "3");
        bind(binding.btn0, "0");
        bind(binding.btnDot, ".");
        bind(binding.btnDel, "⌫");
        bind(binding.btnSin, "sin(");
        bind(binding.btnCos, "cos(");
        bind(binding.btnTan, "tan(");
        bind(binding.btnLn, "ln(");
        bind(binding.btnLog, "log(");
        bind(binding.btnSqrt, "√");
        bind(binding.btnPow, "^");
        bind(binding.btnSq, "^2");
        bind(binding.btnPi, "π");
        bind(binding.btnE, "e");
        AnimUtils.pressScale(binding.btnAdv);
        binding.btnAdv.setOnClickListener(v -> {
            boolean show = binding.rowAdvA.getVisibility() != View.VISIBLE;
            binding.rowAdvA.setVisibility(show ? View.VISIBLE : View.GONE);
            binding.rowAdvB.setVisibility(show ? View.VISIBLE : View.GONE);
        });
        AnimUtils.pressScale(binding.btnEq);
        binding.btnEq.setOnClickListener(v -> equals(v));
        AnimUtils.pressScale(binding.btnHist);
        binding.btnHist.setOnClickListener(v -> showHistory());
        restore();
        syncPreview();
    }

    // ------------------------------------------------- auto-save (app default)

    private static final String CALC_PREFS = "calc_store";

    private void autoSave() {
        try {
            if (binding == null) {
                return;
            }
            org.json.JSONArray arr = new org.json.JSONArray();
            for (String[] row : HISTORY) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("l", row[1]);
                o.put("r", row[0]);
                arr.put(o);
            }
            requireContext().getSharedPreferences(CALC_PREFS, 0).edit()
                    .putString("history", arr.toString())
                    .apply();
        } catch (Throwable t) {
        }
    }

    private void restore() {
        try {
            android.content.SharedPreferences p =
                    requireContext().getSharedPreferences(CALC_PREFS, 0);
            // the screen itself always starts clean; only history persists
            HISTORY.clear();
            org.json.JSONArray arr = new org.json.JSONArray(p.getString("history", "[]"));
            // walk oldest to newest so the live in-session order is kept
            int from = Math.max(0, arr.length() - 40);
            for (int i = from; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                HISTORY.add(new String[]{o.optString("r", "0"), o.optString("l", "")});
            }
        } catch (Throwable t) {
        }
    }

    private void bind(View button, final String token) {
        // pressScale is installed once here; the click itself stays
        // lean so rapid key mashing never queues duplicate listeners.
        AnimUtils.pressScale(button);
        button.setOnClickListener(v -> {
            if ("C".equals(token)) {
                binding.calcExpr.setText("0");
                binding.calcResult.setText("");
                syncPreview();
                return;
            }
            if ("⌫".equals(token)) {
                String cur = binding.calcExpr.getText().toString();
                if (cur == null || cur.isEmpty()) {
                    cur = "0";
                } else {
                    cur = cur.substring(0, cur.length() - 1);
                    if (cur.isEmpty()) {
                        cur = "0";
                    }
                }
                binding.calcExpr.setText(cur);
                syncPreview();
                return;
            }
            append(token);
        });
    }

    private void append(String token) {
        String cur = binding.calcExpr.getText().toString();
        if (cur.isEmpty() && token.startsWith("^")) {
            return;
        }
        if (cur.equals("0") && !isOperator(token) && !")".equals(token)) {
            cur = "";
        }
        if ("( )".equals(token)) {
            token = pickParen(cur);
        } else if (isOperator(token)) {
            if (cur.isEmpty()) {
                if (!"−".equals(token) && !"+".equals(token)) {
                    return;
                }
            } else if (isOperator(lastChar(cur))) {
                cur = cur.substring(0, cur.length() - 1);
            }
        } else if (".".equals(token)) {
            String last = lastChar(cur);
            if (cur.isEmpty() || isOperator(last) || "(".equals(last)) {
                token = "0.";
            } else {
                int j = cur.length() - 1;
                while (j >= 0 && !isOperator(cur.charAt(j) + "") && !"(".equals(cur.charAt(j))
                        && !")".equals(cur.charAt(j))) {
                    if (cur.charAt(j) == '.') {
                        return;
                    }
                    j--;
                }
            }
        } else if (")".equals(token)) {
            return;
        }
        cur = cur + token;
        binding.calcExpr.setText(cur);
        syncPreview();
    }

    private void equals(View view) {
        String expr = binding.calcExpr.getText().toString();
        String result = CalcEngine.eval(expr);
        if (result == null) {
            binding.calcResult.setText("Error");
            // only real errors buzz the heavier error pattern
            com.nivodev.nivotools.util.Haptics.action(view,
                    com.nivodev.nivotools.util.Haptics.TYPE_ERROR);
            return;
        }
        String shown = new StringBuilder().append(expr).append(" = ").append(result).toString();
        if (!HISTORY.isEmpty() && shown.equals(HISTORY.get(HISTORY.size() - 1)[1])) {
            HISTORY.remove(HISTORY.size() - 1);
        }
        HISTORY.add(new String[]{result, shown});
        if (HISTORY.size() > 40) {
            HISTORY.remove(0);
        }
        binding.calcExpr.setText(result);
        binding.calcResult.setText("");
        syncPreview();
        autoSave();
    }

    private void showHistory() {
        if (HISTORY.isEmpty()) {
            return;
        }
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(requireContext());
        View content = getLayoutInflater().inflate(com.nivodev.nivotools.R.layout.sheet_calc_history, null, false);
        sheet.setContentView(content);
        com.nivodev.nivotools.databinding.SheetCalcHistoryBinding hb =
                com.nivodev.nivotools.databinding.SheetCalcHistoryBinding.bind(content);
        hb.sheetClose.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            sheet.dismiss();
        });
        hb.sheetClear.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            HISTORY.clear();
            hb.historyList.removeAllViews();
            autoSave();
        });
        for (final String[] row : HISTORY) {
            TextView line = new TextView(requireContext());
            line.setPadding(0, 14, 0, 14);
            line.setText(row[1]);
            line.setTextSize(15f);
            line.setTextColor(ContextCompat.getColor(requireContext(),
                    com.nivodev.nivotools.R.color.light_on_surface));
            line.setOnClickListener(v2 -> {
                AnimUtils.pressScale(v2);
                binding.calcExpr.setText(row[0]);
                binding.calcResult.setText("");
                syncPreview();
                sheet.dismiss();
            });
            hb.historyList.addView(line);
        }
        sheet.show();
    }

    private void syncPreview() {
        String result = CalcEngine.eval(binding.calcExpr.getText().toString());
        if (result != null && !result.contentEquals(binding.calcExpr.getText())) {
            binding.calcResult.setText("=" + result);
        } else {
            binding.calcResult.setText("");
        }
    }

    private String pickParen(String cur) {
        String last = lastChar(cur);
        int open = 0;
        for (int k = 0; k < cur.length(); k++) {
            if (cur.charAt(k) == '(') {
                open++;
            } else if (cur.charAt(k) == ')') {
                open--;
            }
        }
        if (open > 0 && !isOperator(last) && !"(".equals(last) && !cur.isEmpty()) {
            return ")";
        }
        return "(";
    }

    private boolean isOperator(String token) {
        if (token == null || token.length() != 1) {
            return false;
        }
        char c = token.charAt(0);
        return c == '+' || c == '−' || c == '×' || c == '÷' || c == '^';
    }

    private String lastChar(String s) {
        return s == null || s.isEmpty() ? "" : s.substring(s.length() - 1);
    }
}
