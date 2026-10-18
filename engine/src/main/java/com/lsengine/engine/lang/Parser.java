package com.lsengine.engine.lang;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Рекурсивный парсер языка LS (синтаксис в духе Lua).
 */
public final class Parser {

    private static final List<String> BLOCK_ENDERS = Arrays.asList("end", "else", "elseif", "until");

    private final List<Token> tokens;
    private int index;

    public Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    public static Ast.Block parse(String source) throws LsError {
        Parser p = new Parser(new Lexer(source).tokenize());
        return p.parseChunk();
    }

    // ---------------- служебное ----------------

    private Token peek() {
        return tokens.get(Math.min(index, tokens.size() - 1));
    }

    private Token peek(int ahead) {
        return tokens.get(Math.min(index + ahead, tokens.size() - 1));
    }

    private boolean check(Token.Type type, String text) {
        Token t = peek();
        return t.type == type && (text == null || t.text.equals(text));
    }

    private boolean checkKeyword(String kw) {
        return peek().isKeyword(kw);
    }

    private boolean checkOp(String op) {
        return peek().isOp(op);
    }

    private Token advance() {
        Token t = peek();
        if (index < tokens.size() - 1) {
            index++;
        }
        return t;
    }

    private Token expect(Token.Type type, String text, String what) throws LsError {
        Token t = peek();
        if (t.type != type || (text != null && !t.text.equals(text))) {
            throw new LsError("Ожидалось " + what + ", получено '" + t.text + "'", t.line, t.col);
        }
        return advance();
    }

    private void expectKeyword(String kw) throws LsError {
        Token t = peek();
        if (!t.isKeyword(kw)) {
            throw new LsError("Ожидалось '" + kw + "', получено '" + t.text + "'", t.line, t.col);
        }
        advance();
    }

    private Token expectOp(String op) throws LsError {
        Token t = peek();
        if (!t.isOp(op)) {
            throw new LsError("Ожидалось '" + op + "', получено '" + t.text + "'", t.line, t.col);
        }
        return advance();
    }

    private <T extends Ast.Node> T pos(T node, Token t) {
        node.line = t.line;
        node.col = t.col;
        return node;
    }

    // ---------------- блоки и операторы ----------------

    private Ast.Block parseChunk() throws LsError {
        Ast.Block block = new Ast.Block();
        block.line = peek().line;
        block.col = peek().col;
        parseBlockInto(block, null);
        if (!peek().is(Token.Type.EOF)) {
            Token t = peek();
            throw new LsError("Неожиданный символ '" + t.text + "'", t.line, t.col);
        }
        return block;
    }

    /**
     * Разбирает последовательность операторов до terminator (ключевое слово) либо до конца файла.
     */
    private void parseBlockInto(Ast.Block block, String terminator) throws LsError {
        while (true) {
            Token t = peek();
            if (t.is(Token.Type.EOF)) {
                if (terminator != null) {
                    throw new LsError("Неожиданный конец файла, ожидалось '" + terminator + "'", t.line, t.col);
                }
                return;
            }
            if (t.type == Token.Type.KEYWORD && BLOCK_ENDERS.contains(t.text)) {
                if (terminator != null && !t.text.equals(terminator)) {
                    throw new LsError("Ожидалось '" + terminator + "', получено '" + t.text + "'", t.line, t.col);
                }
                return;
            }
            if (t.isOp(";")) {
                advance();
                continue;
            }
            if (t.isKeyword("return")) {
                block.stats.add(parseReturn());
                return;
            }
            Ast.Stat stat = parseStatement();
            if (stat != null) {
                block.stats.add(stat);
            }
        }
    }

    private Ast.Stat parseReturn() throws LsError {
        Token kw = advance();
        Ast.Return ret = new Ast.Return(null);
        ret.line = kw.line;
        ret.col = kw.col;
        Token t = peek();
        boolean ends = t.is(Token.Type.EOF)
                || (t.type == Token.Type.KEYWORD && BLOCK_ENDERS.contains(t.text))
                || t.isOp(";");
        if (!ends) {
            ret.value = parseExpr();
        }
        if (checkOp(";")) {
            advance();
        }
        return ret;
    }

    private Ast.Stat parseStatement() throws LsError {
        Token t = peek();

        if (t.isKeyword("local")) {
            advance();
            if (peek().isKeyword("function")) {
                // local function name(...) ... end
                advance();
                Token fnTok = expect(Token.Type.NAME, null, "имя функции");
                List<String> fnParams = new ArrayList<>();
                expectOp("(");
                parseParams(fnParams);
                expectOp(")");
                Ast.Block fnBody = new Ast.Block();
                pos(fnBody, fnTok);
                parseBlockInto(fnBody, "end");
                expectKeyword("end");
                return pos(new Ast.FuncDecl(fnTok.text, fnParams, fnBody, true), fnTok);
            }
            Token nameTok = expect(Token.Type.NAME, null, "имя переменной");
            Ast.VarDecl decl = new Ast.VarDecl(nameTok.text, null);
            pos(decl, nameTok);
            if (checkOp("=")) {
                advance();
                decl.value = parseExpr();
            }
            return decl;
        }

        if (t.isKeyword("global")) {
            advance();
            Token nameTok = expect(Token.Type.NAME, null, "имя переменной");
            Ast.Expr target = new Ast.Name(nameTok.text);
            pos(target, nameTok);
            Ast.Expr value = null;
            if (checkOp("=")) {
                advance();
                value = parseExpr();
            } else {
                value = new Ast.NilLit();
                pos(value, nameTok);
            }
            return new Ast.Assign(target, value);
        }

        if (t.isKeyword("function")) {
            advance();
            Token nameTok = expect(Token.Type.NAME, null, "имя функции");
            List<String> params = new ArrayList<>();
            expectOp("(");
            parseParams(params);
            expectOp(")");
            Ast.Block body = new Ast.Block();
            pos(body, nameTok);
            parseBlockInto(body, "end");
            expectKeyword("end");
            Ast.FuncDecl decl = new Ast.FuncDecl(nameTok.text, params, body, false);
            return pos(decl, nameTok);
        }

        if (t.isKeyword("if")) {
            return parseIf();
        }

        if (t.isKeyword("while")) {
            advance();
            Ast.Expr cond = parseExpr();
            expectKeyword("do");
            Ast.Block body = new Ast.Block();
            pos(body, t);
            parseBlockInto(body, "end");
            expectKeyword("end");
            return pos(new Ast.While(cond, body), t);
        }

        if (t.isKeyword("do")) {
            advance();
            Ast.Block body = new Ast.Block();
            pos(body, t);
            parseBlockInto(body, "end");
            expectKeyword("end");
            return pos(new Ast.DoStat(body), t);
        }

        if (t.isKeyword("repeat")) {
            advance();
            Ast.Block body = new Ast.Block();
            pos(body, t);
            parseBlockInto(body, "until");
            expectKeyword("until");
            Ast.Expr cond = parseExpr();
            // repeat ... until cond  ==>  while not cond do ... end
            Ast.Expr notCond = new Ast.Un("not", cond);
            pos(notCond, t);
            return pos(new Ast.While(notCond, body), t);
        }

        if (t.isKeyword("for")) {
            return parseFor();
        }

        if (t.isKeyword("break")) {
            advance();
            return pos(new Ast.Break(), t);
        }

        // Присваивание или вызов.
        Ast.Expr expr = parseSuffixedExpr();
        String compound = compoundOperator();
        if (compound != null) {
            advance();
            Ast.Expr value = parseExpr();
            if (!(expr instanceof Ast.Name) && !(expr instanceof Ast.Index)) {
                throw new LsError("Некорректная цель присваивания", t.line, t.col);
            }
            return pos(new Ast.Assign(expr, value, compound), t);
        }
        if (checkOp("=") || checkOp(",")) {
            List<Ast.Expr> targets = new ArrayList<>();
            targets.add(expr);
            while (checkOp(",")) {
                advance();
                targets.add(parseSuffixedExpr());
            }
            expectOp("=");
            List<Ast.Expr> values = parseExprList();
            if (targets.size() != values.size()) {
                throw new LsError("Число переменных и значений не совпадает", t.line, t.col);
            }
            Ast.Stat last = null;
            for (int i = 0; i < targets.size(); i++) {
                Ast.Expr target = targets.get(i);
                if (!(target instanceof Ast.Name) && !(target instanceof Ast.Index)) {
                    throw new LsError("Некорректная цель присваивания", t.line, t.col);
                }
                last = pos(new Ast.Assign(target, values.get(i)), t);
            }
            return last;
        }
        if (!(expr instanceof Ast.Call)) {
            throw new LsError("Ожидалось присваивание или вызов функции", t.line, t.col);
        }
        return pos(new Ast.ExprStat(expr), t);
    }

    private Ast.Stat parseIf() throws LsError {
        Token kw = advance(); // if
        Ast.Expr cond = parseExpr();
        expectKeyword("then");
        Ast.Block thenBlock = new Ast.Block();
        pos(thenBlock, kw);
        parseBlockInto(thenBlock, null);
        Ast.Block elseBlock = null;
        // parseBlockInto останавливается на elseif/else/end
        if (checkKeyword("elseif")) {
            elseBlock = new Ast.Block();
            Ast.Stat nested = parseIf();
            elseBlock.stats.add(nested);
            return pos(new Ast.If(cond, thenBlock, elseBlock), kw);
        }
        if (checkKeyword("else")) {
            advance();
            elseBlock = new Ast.Block();
            pos(elseBlock, kw);
            parseBlockInto(elseBlock, null);
        }
        expectKeyword("end");
        return pos(new Ast.If(cond, thenBlock, elseBlock), kw);
    }

    private Ast.Stat parseFor() throws LsError {
        Token kw = advance(); // for
        Token nameTok = expect(Token.Type.NAME, null, "имя переменной");
        if (checkOp("=")) {
            advance();
            Ast.Expr start = parseExpr();
            expectOp(",");
            Ast.Expr limit = parseExpr();
            Ast.Expr step = null;
            if (checkOp(",")) {
                advance();
                step = parseExpr();
            }
            expectKeyword("do");
            Ast.Block body = new Ast.Block();
            pos(body, nameTok);
            parseBlockInto(body, "end");
            expectKeyword("end");
            return pos(new Ast.NumFor(nameTok.text, start, limit, step, body), kw);
        }
        List<String> names = new ArrayList<>();
        names.add(nameTok.text);
        while (checkOp(",")) {
            advance();
            Token n = expect(Token.Type.NAME, null, "имя переменной");
            names.add(n.text);
        }
        expectKeyword("in");
        Ast.Expr iterable = parseExpr();
        expectKeyword("do");
        Ast.Block body = new Ast.Block();
        pos(body, nameTok);
        parseBlockInto(body, "end");
        expectKeyword("end");
        return pos(new Ast.GenFor(names, iterable, body), kw);
    }

    private void parseParams(List<String> params) throws LsError {
        if (checkOp(")")) {
            return;
        }
        while (true) {
            Token n = expect(Token.Type.NAME, null, "имя параметра");
            params.add(n.text);
            if (checkOp(",")) {
                advance();
                continue;
            }
            break;
        }
    }

    // ---------------- выражения ----------------

    public Ast.Expr parseExpr() throws LsError {
        Ast.Expr cond = parseOr();
        if (checkOp("?")) {
            advance();
            Ast.Expr yes = parseExpr();
            expectOp(":");
            Ast.Expr no = parseExpr();
            return new Ast.Ternary(cond, yes, no);
        }
        return cond;
    }

    /** Составной оператор присваивания ("+=" и подобные) либо null. */
    private String compoundOperator() {
        if (checkOp("+=")) {
            return "+";
        }
        if (checkOp("-=")) {
            return "-";
        }
        if (checkOp("*=")) {
            return "*";
        }
        if (checkOp("/=")) {
            return "/";
        }
        if (checkOp("%=")) {
            return "%";
        }
        return null;
    }

    private List<Ast.Expr> parseExprList() throws LsError {
        List<Ast.Expr> list = new ArrayList<>();
        list.add(parseExpr());
        while (checkOp(",")) {
            advance();
            list.add(parseExpr());
        }
        return list;
    }

    private Ast.Expr parseOr() throws LsError {
        Ast.Expr left = parseAnd();
        while (checkKeyword("or")) {
            Token t = advance();
            Ast.Expr right = parseAnd();
            left = pos(new Ast.Bin("or", left, right), t);
        }
        return left;
    }

    private Ast.Expr parseAnd() throws LsError {
        Ast.Expr left = parseNot();
        while (checkKeyword("and")) {
            Token t = advance();
            Ast.Expr right = parseNot();
            left = pos(new Ast.Bin("and", left, right), t);
        }
        return left;
    }

    private Ast.Expr parseNot() throws LsError {
        if (checkKeyword("not")) {
            Token t = advance();
            Ast.Expr operand = parseNot();
            return pos(new Ast.Un("not", operand), t);
        }
        return parseComparison();
    }

    private Ast.Expr parseComparison() throws LsError {
        Ast.Expr left = parseConcat();
        while (checkOp("==") || checkOp("~=") || checkOp("<") || checkOp("<=") || checkOp(">") || checkOp(">=")) {
            Token t = advance();
            Ast.Expr right = parseConcat();
            left = pos(new Ast.Bin(t.text, left, right), t);
        }
        return left;
    }

    private Ast.Expr parseConcat() throws LsError {
        Ast.Expr left = parseAdditive();
        if (checkOp("..")) {
            Token t = advance();
            Ast.Expr right = parseConcat(); // правоассоциативно
            return pos(new Ast.Bin("..", left, right), t);
        }
        return left;
    }

    private Ast.Expr parseAdditive() throws LsError {
        Ast.Expr left = parseMultiplicative();
        while (checkOp("+") || checkOp("-")) {
            Token t = advance();
            Ast.Expr right = parseMultiplicative();
            left = pos(new Ast.Bin(t.text, left, right), t);
        }
        return left;
    }

    private Ast.Expr parseMultiplicative() throws LsError {
        Ast.Expr left = parseUnary();
        while (checkOp("*") || checkOp("/") || checkOp("%")) {
            Token t = advance();
            Ast.Expr right = parseUnary();
            left = pos(new Ast.Bin(t.text, left, right), t);
        }
        return left;
    }

    private Ast.Expr parseUnary() throws LsError {
        if (checkOp("-") || checkOp("#")) {
            Token t = advance();
            Ast.Expr operand = parseUnary();
            return pos(new Ast.Un(t.text, operand), t);
        }
        return parsePower();
    }

    private Ast.Expr parsePower() throws LsError {
        Ast.Expr base = parseSuffixedExpr();
        if (checkOp("^")) {
            Token t = advance();
            Ast.Expr exp = parseUnary(); // правоассоциативно
            return pos(new Ast.Bin("^", base, exp), t);
        }
        return base;
    }

    private Ast.Expr parseSuffixedExpr() throws LsError {
        Ast.Expr expr = parsePrimary();
        while (true) {
            Token t = peek();
            if (t.isOp(".")) {
                advance();
                Token name = expect(Token.Type.NAME, null, "имя поля");
                expr = pos(new Ast.Index(expr, new Ast.StrLit(name.text), true), t);
            } else if (t.isOp("[")) {
                advance();
                Ast.Expr key = parseExpr();
                expectOp("]");
                expr = pos(new Ast.Index(expr, key, false), t);
            } else if (t.isOp(":")) {
                advance();
                Token name = expect(Token.Type.NAME, null, "имя метода");
                List<Ast.Expr> args = parseCallArgs();
                expr = pos(new Ast.Call(pos(new Ast.Index(expr, new Ast.StrLit(name.text), true), t), args, true), t);
            } else if (t.isOp("(") || t.is(Token.Type.STRING)) {
                List<Ast.Expr> args = parseCallArgs();
                expr = pos(new Ast.Call(expr, args, false), t);
            } else {
                return expr;
            }
        }
    }

    private List<Ast.Expr> parseCallArgs() throws LsError {
        List<Ast.Expr> args = new ArrayList<>();
        Token t = peek();
        if (t.is(Token.Type.STRING)) {
            advance();
            args.add(pos(new Ast.StrLit((String) t.value), t));
            return args;
        }
        if (t.isOp("{")) {
            args.add(parseTable());
            return args;
        }
        expectOp("(");
        if (!checkOp(")")) {
            args.addAll(parseExprList());
        }
        expectOp(")");
        return args;
    }

    private Ast.Expr parsePrimary() throws LsError {
        Token t = peek();
        switch (t.type) {
            case NUMBER:
                advance();
                return pos(new Ast.NumLit((Double) t.value), t);
            case STRING:
                advance();
                return pos(new Ast.StrLit((String) t.value), t);
            case NAME:
                advance();
                return pos(new Ast.Name(t.text), t);
            default:
                break;
        }
        if (t.isKeyword("true")) {
            advance();
            return pos(new Ast.BoolLit(true), t);
        }
        if (t.isKeyword("false")) {
            advance();
            return pos(new Ast.BoolLit(false), t);
        }
        if (t.isKeyword("nil")) {
            advance();
            return pos(new Ast.NilLit(), t);
        }
        if (t.isKeyword("function")) {
            advance();
            List<String> params = new ArrayList<>();
            expectOp("(");
            parseParams(params);
            expectOp(")");
            Ast.Block body = new Ast.Block();
            pos(body, t);
            parseBlockInto(body, "end");
            expectKeyword("end");
            return pos(new Ast.Func(params, body), t);
        }
        if (t.isOp("{")) {
            return parseTable();
        }
        if (t.isOp("(")) {
            advance();
            Ast.Expr inner = parseExpr();
            expectOp(")");
            return inner;
        }
        throw new LsError("Неожиданный символ '" + t.text + "'", t.line, t.col);
    }

    private Ast.Expr parseTable() throws LsError {
        Token open = expectOp("{");
        Ast.TableLit table = new Ast.TableLit();
        pos(table, open);
        while (!checkOp("}")) {
            if (checkOp("[")) {
                advance();
                Ast.Expr keyExpr = parseExpr();
                expectOp("]");
                expectOp("=");
                Ast.Expr value = parseExpr();
                if (keyExpr instanceof Ast.StrLit) {
                    table.hashKeys.add(((Ast.StrLit) keyExpr).value);
                    table.hashValues.add(value);
                } else if (keyExpr instanceof Ast.NumLit) {
                    table.numKeys.add(((Ast.NumLit) keyExpr).value);
                    table.numValues.add(value);
                } else {
                    throw new LsError("Ключ таблицы должен быть строкой или числом", keyExpr.line, keyExpr.col);
                }
            } else if (peek().is(Token.Type.NAME) && peek(1).isOp("=")) {
                Token name = advance();
                advance(); // '='
                table.hashKeys.add(name.text);
                table.hashValues.add(parseExpr());
            } else {
                table.arrayItems.add(parseExpr());
            }
            if (checkOp(",") || checkOp(";")) {
                advance();
                continue;
            }
            break;
        }
        expectOp("}");
        return table;
    }
}
