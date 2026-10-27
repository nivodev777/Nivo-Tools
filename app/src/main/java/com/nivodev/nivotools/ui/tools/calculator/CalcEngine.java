package com.nivodev.nivotools.ui.tools.calculator;

public final class CalcEngine {

    /** Known identifiers, longest first. */
    private static final String[] FNAMES = {"sqrt", "pi", "sin", "cos", "tan", "log", "ln"};

    private final String s;
    private int i;

    private CalcEngine(String s) {
        this.s = s;
    }

    /** Evaluates an expression; returns null when malformed. */
    public static String eval(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String expr = raw.replace('×', '*').replace('÷', '/')
                .replace('−', '-').replaceAll(" +", "").trim();
        try {
            CalcEngine e = new CalcEngine(expr);
            double v = e.parseExpr();
            if (e.i < e.s.length()) {
                return null;
            }
            return format(v);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String format(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return null;
        }
        if (v == Math.floor(v) && Math.abs(v) < 1e15) {
            return String.valueOf((long) v);
        }
        // Round to 12 decimals then trim: 0.1 + 0.2 reads 0.3 instead of
        // 0.30000000000000004, while meaningful precision stays visible.
        return java.math.BigDecimal.valueOf(v)
                .setScale(12, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    private char peek() {
        while (i < s.length() && s.charAt(i) == ' ') {
            i++;
        }
        return i < s.length() ? s.charAt(i) : '\0';
    }

    private double parseExpr() {
        double v = parseTerm();
        while (true) {
            char c = peek();
            if (c == '+' || c == '-') {
                i++;
                double r = parseTerm();
                v = c == '+' ? v + r : v - r;
            } else {
                break;
            }
        }
        return v;
    }

    private double parseTerm() {
        double v = parsePower();
        while (true) {
            char c = peek();
            if (c == '*' || c == '/') {
                i++;
                double r = parsePower();
                v = c == '*' ? v * r : (r == 0d ? Double.NaN : v / r);
            } else if (c == '(' || c == '√' || Character.isLetter(c)) {
                double r = parsePower();
                v = v * r;
            } else {
                break;
            }
        }
        return v;
    }

    /** Power binds tighter than multiply and is right-associative. */
    private double parsePower() {
        double base = parseUnary();
        if (peek() == '^') {
            i++;
            double exp = parsePower();
            return Math.pow(base, exp);
        }
        return base;
    }

    private double parseUnary() {
        char c = peek();
        if (c == '-') {
            i++;
            return -parseUnary();
        }
        if (c == '+') {
            i++;
            return parseUnary();
        }
        return parsePostfix();
    }

    private double parsePostfix() {
        double v = parsePrimary();
        while (peek() == '%') {
            i++;
            v = v / 100d;
        }
        return v;
    }

    private double parsePrimary() {
        char c = peek();
        if (c == '(') {
            i++;
            double v = parseExpr();
            if (peek() != ')') {
                throw new IllegalStateException();
            }
            i++;
            return v;
        }
        if (c == '√') {
            i++;
            double arg = peek() == '(' ? parseParenArg() : parsePrimary();
            return Math.sqrt(arg);
        }
        if (Character.isLetter(c)) {
            if (c == 'π') {
                i++;
                return Math.PI;
            }
            for (String n : FNAMES) {
                if (s.regionMatches(i, n, 0, n.length())) {
                    i += n.length();
                    if ("pi".equals(n)) {
                        return Math.PI;
                    }
                    return applyFn(n, parseParenArg());
                }
            }
            if (c == 'e') {
                i++;
                return Math.E;
            }
            throw new IllegalStateException();
        }
        int start = i;
        while (i < s.length()) {
            char d = s.charAt(i);
            if ((d >= '0' && d <= '9') || d == '.') {
                i++;
            } else {
                break;
            }
        }
        if (i == start) {
            throw new IllegalStateException();
        }
        double out = Double.parseDouble(s.substring(start, i));
        return out;
    }

    private double parseParenArg() {
        if (peek() != '(') {
            return parsePrimary();
        }
        i++;
        double v = parseExpr();
        if (peek() != ')') {
            throw new IllegalStateException();
        }
        i++;
        return v;
    }

    /** Applies a known function name; pi is a constant and bypasses this. */
    private double applyFn(String name, double arg) {
        if ("pi".equals(name)) {
            return Math.PI;
        }
        if ("sin".equals(name)) {
            return Math.sin(Math.toRadians(arg));
        }
        if ("cos".equals(name)) {
            return Math.cos(Math.toRadians(arg));
        }
        if ("tan".equals(name)) {
            return Math.tan(Math.toRadians(arg));
        }
        if ("log".equals(name)) {
            return Math.log10(arg);
        }
        if ("ln".equals(name)) {
            return Math.log(arg);
        }
        if ("sqrt".equals(name)) {
            return Math.sqrt(arg);
        }
        throw new IllegalStateException();
    }
}
