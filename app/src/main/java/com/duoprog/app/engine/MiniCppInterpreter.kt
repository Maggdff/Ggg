package com.duoprog.app.engine

// ===================== Tokens =====================
data class Token(val type: String, val text: String)

class BreakSignal : RuntimeException()

fun preprocess(src: String): String {
    var s = src.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
    s = s.lines().joinToString("\n") { line ->
        val idx = line.indexOf("//")
        if (idx >= 0) line.substring(0, idx) else line
    }
    return s
}

fun extractExecutableBlock(src: String): String {
    val mainIdx = src.indexOf("main")
    if (mainIdx == -1) return src
    val braceStart = src.indexOf('{', mainIdx)
    if (braceStart == -1) return src
    var depth = 0
    var i = braceStart
    while (i < src.length) {
        when (src[i]) {
            '{' -> depth++
            '}' -> { depth--; if (depth == 0) return src.substring(braceStart + 1, i) }
        }
        i++
    }
    return src.substring(braceStart + 1)
}

fun tokenize(src: String): List<Token> {
    val tokens = mutableListOf<Token>()
    var i = 0
    val n = src.length
    val punct = "+-*/%=<>!(){};,:"
    while (i < n) {
        val c = src[i]
        when {
            c.isWhitespace() -> i++
            c == '"' -> {
                val sb = StringBuilder(); i++
                while (i < n && src[i] != '"') {
                    if (src[i] == '\\' && i + 1 < n) {
                        when (src[i + 1]) {
                            'n' -> { sb.append('\n'); i += 2 }
                            't' -> { sb.append('\t'); i += 2 }
                            '"' -> { sb.append('"'); i += 2 }
                            '\\' -> { sb.append('\\'); i += 2 }
                            else -> { sb.append(src[i]); i++ }
                        }
                    } else { sb.append(src[i]); i++ }
                }
                if (i < n) i++
                tokens.add(Token("STR", sb.toString()))
            }
            c == '\'' -> {
                i++
                var ch = ' '
                if (i < n && src[i] == '\\' && i + 1 < n) {
                    ch = when (src[i + 1]) { 'n' -> '\n'; 't' -> '\t'; else -> src[i + 1] }
                    i += 2
                } else if (i < n) { ch = src[i]; i++ }
                if (i < n && src[i] == '\'') i++
                tokens.add(Token("CHAR", ch.toString()))
            }
            c.isDigit() -> {
                val start = i
                while (i < n && src[i].isDigit()) i++
                if (i < n && src[i] == '.') {
                    i++
                    while (i < n && src[i].isDigit()) i++
                    tokens.add(Token("FNUM", src.substring(start, i)))
                } else tokens.add(Token("NUM", src.substring(start, i)))
            }
            c.isLetter() || c == '_' -> {
                val start = i
                while (i < n && (src[i].isLetterOrDigit() || src[i] == '_')) i++
                tokens.add(Token("IDENT", src.substring(start, i)))
            }
            c == '+' && i + 1 < n && src[i + 1] == '+' -> { tokens.add(Token("OP", "++")); i += 2 }
            c == '-' && i + 1 < n && src[i + 1] == '-' -> { tokens.add(Token("OP", "--")); i += 2 }
            c == '=' && i + 1 < n && src[i + 1] == '=' -> { tokens.add(Token("OP", "==")); i += 2 }
            c == '!' && i + 1 < n && src[i + 1] == '=' -> { tokens.add(Token("OP", "!=")); i += 2 }
            c == '<' && i + 1 < n && src[i + 1] == '=' -> { tokens.add(Token("OP", "<=")); i += 2 }
            c == '>' && i + 1 < n && src[i + 1] == '=' -> { tokens.add(Token("OP", ">=")); i += 2 }
            c == '<' && i + 1 < n && src[i + 1] == '<' -> { tokens.add(Token("OP", "<<")); i += 2 }
            c == '>' && i + 1 < n && src[i + 1] == '>' -> { tokens.add(Token("OP", ">>")); i += 2 }
            c == '&' && i + 1 < n && src[i + 1] == '&' -> { tokens.add(Token("OP", "&&")); i += 2 }
            c == '|' && i + 1 < n && src[i + 1] == '|' -> { tokens.add(Token("OP", "||")); i += 2 }
            punct.contains(c) -> { tokens.add(Token("OP", c.toString())); i++ }
            else -> i++
        }
    }
    tokens.add(Token("EOF", ""))
    return tokens
}

// ===================== AST =====================
sealed class Expr
data class NumberLit(val value: Double, val isInt: Boolean) : Expr()
data class StringLit(val value: String) : Expr()
data class CharLit(val value: Char) : Expr()
data class VarRef(val name: String) : Expr()
data class BinaryExpr(val op: String, val left: Expr, val right: Expr) : Expr()
data class UnaryExpr(val op: String, val expr: Expr) : Expr()
data class IncDecExpr(val name: String, val isInc: Boolean, val isPrefix: Boolean) : Expr()
data class AssignExpr(val name: String, val value: Expr) : Expr()
data class GroupExpr(val inner: Expr) : Expr()
object EndlLit : Expr()

sealed class Stmt
data class VarDeclStmt(val varType: String, val decls: List<Pair<String, Expr?>>) : Stmt()
data class AssignStmt(val name: String, val expr: Expr) : Stmt()
data class ExprStmt(val expr: Expr) : Stmt()
data class CinStmt(val vars: List<String>) : Stmt()
data class CoutStmt(val exprs: List<Expr>) : Stmt()
data class IfStmt(val cond: Expr, val thenBranch: Stmt, val elseBranch: Stmt?) : Stmt()
data class BlockStmt(val stmts: List<Stmt>) : Stmt()
data class ForStmt(val init: Stmt?, val cond: Expr?, val update: Stmt?, val body: Stmt) : Stmt()
data class WhileStmt(val cond: Expr, val body: Stmt) : Stmt()
data class DoWhileStmt(val body: Stmt, val cond: Expr) : Stmt()
data class SwitchCase(val value: Expr, val body: List<Stmt>)
data class SwitchStmt(val subject: Expr, val cases: List<SwitchCase>, val defaultBody: List<Stmt>?) : Stmt()
object BreakStmt : Stmt()
object EmptyStmt : Stmt()

private val TYPE_KEYWORDS = setOf("int", "float", "double", "char", "long", "short")

fun normalizeType(t: String): String = when (t) {
    "int", "short", "long" -> "int"
    "float", "double" -> "double"
    "char" -> "char"
    else -> "int"
}

// ===================== Parser =====================
class Parser(private val tokens: List<Token>) {
    private var pos = 0
    private fun peek(): Token = tokens[pos]
    private fun advance(): Token = tokens[pos++]
    private fun checkOp(text: String) = peek().type == "OP" && peek().text == text
    private fun checkIdent(text: String) = peek().type == "IDENT" && peek().text == text
    private fun expectOp(text: String): Token {
        if (!checkOp(text)) throw RuntimeException("توقعت الرمز '$text' ووجدت '${peek().text}'")
        return advance()
    }
    private fun expectIdent(): Token {
        if (peek().type != "IDENT") throw RuntimeException("توقعت اسمًا ووجدت '${peek().text}'")
        return advance()
    }
    fun isAtEnd() = peek().type == "EOF"

    fun parseProgram(): List<Stmt> {
        val stmts = mutableListOf<Stmt>()
        while (!isAtEnd()) stmts.add(parseStatement())
        return stmts
    }

    fun parseStatement(): Stmt {
        val tok = peek()
        if (tok.type == "IDENT" && tok.text in TYPE_KEYWORDS) return parseVarDecl()
        if (checkIdent("if")) return parseIf()
        if (checkIdent("for")) return parseFor()
        if (checkIdent("while")) return parseWhile()
        if (checkIdent("do")) return parseDoWhile()
        if (checkIdent("switch")) return parseSwitch()
        if (checkIdent("break")) { advance(); expectOp(";"); return BreakStmt }
        if (checkOp("{")) return parseBlock()
        if (checkIdent("cin")) return parseCin()
        if (checkIdent("cout")) return parseCout()
        if (checkOp(";")) { advance(); return EmptyStmt }
        val e = parseExpr()
        expectOp(";")
        return ExprStmt(e)
    }

    private fun parseVarDecl(): Stmt {
        val typeTok = advance()
        val normType = normalizeType(typeTok.text)
        val decls = mutableListOf<Pair<String, Expr?>>()
        do {
            val nameTok = expectIdent()
            var initExpr: Expr? = null
            if (checkOp("=")) { advance(); initExpr = parseAssignment() }
            decls.add(nameTok.text to initExpr)
        } while (checkOp(",").also { if (it) advance() })
        expectOp(";")
        return VarDeclStmt(normType, decls)
    }

    private fun parseIf(): Stmt {
        advance(); expectOp("(")
        val cond = parseExpr()
        expectOp(")")
        val thenB = parseStatement()
        var elseB: Stmt? = null
        if (checkIdent("else")) { advance(); elseB = parseStatement() }
        return IfStmt(cond, thenB, elseB)
    }

    private fun parseFor(): Stmt {
        advance(); expectOp("(")
        val init: Stmt? = when {
            checkOp(";") -> { advance(); null }
            peek().type == "IDENT" && peek().text in TYPE_KEYWORDS -> parseVarDecl()
            else -> { val e = parseExpr(); expectOp(";"); ExprStmt(e) }
        }
        val cond: Expr? = if (checkOp(";")) null else parseExpr()
        expectOp(";")
        val update: Stmt? = if (checkOp(")")) null else ExprStmt(parseExpr())
        expectOp(")")
        val body = parseStatement()
        return ForStmt(init, cond, update, body)
    }

    private fun parseWhile(): Stmt {
        advance(); expectOp("(")
        val cond = parseExpr()
        expectOp(")")
        val body = parseStatement()
        return WhileStmt(cond, body)
    }

    private fun parseDoWhile(): Stmt {
        advance()
        val body = parseStatement()
        if (!checkIdent("while")) throw RuntimeException("توقعت while بعد do")
        advance(); expectOp("(")
        val cond = parseExpr()
        expectOp(")"); expectOp(";")
        return DoWhileStmt(body, cond)
    }

    private fun parseSwitch(): Stmt {
        advance(); expectOp("(")
        val subject = parseExpr()
        expectOp(")"); expectOp("{")
        val cases = mutableListOf<SwitchCase>()
        var defaultBody: List<Stmt>? = null
        while (!checkOp("}")) {
            if (checkIdent("case")) {
                advance()
                val valExpr = parseExpr()
                expectOp(":")
                val body = mutableListOf<Stmt>()
                while (!checkIdent("case") && !checkIdent("default") && !checkOp("}")) {
                    body.add(parseStatement())
                }
                cases.add(SwitchCase(valExpr, body))
            } else if (checkIdent("default")) {
                advance(); expectOp(":")
                val body = mutableListOf<Stmt>()
                while (!checkIdent("case") && !checkOp("}")) body.add(parseStatement())
                defaultBody = body
            } else advance()
        }
        expectOp("}")
        return SwitchStmt(subject, cases, defaultBody)
    }

    private fun parseBlock(): Stmt {
        expectOp("{")
        val stmts = mutableListOf<Stmt>()
        while (!checkOp("}")) stmts.add(parseStatement())
        expectOp("}")
        return BlockStmt(stmts)
    }

    private fun parseCin(): Stmt {
        advance()
        val vars = mutableListOf<String>()
        while (checkOp(">>")) { advance(); vars.add(expectIdent().text) }
        expectOp(";")
        return CinStmt(vars)
    }

    private fun parseCout(): Stmt {
        advance()
        val exprs = mutableListOf<Expr>()
        while (checkOp("<<")) { advance(); exprs.add(parseAssignment()) }
        expectOp(";")
        return CoutStmt(exprs)
    }

    // ---- Expressions ----
    fun parseExpr(): Expr = parseAssignment()

    private fun parseAssignment(): Expr {
        if (peek().type == "IDENT" && tokens[pos + 1].type == "OP" && tokens[pos + 1].text == "=") {
            val name = advance().text
            advance()
            val value = parseAssignment()
            return AssignExpr(name, value)
        }
        return parseLogicalOr()
    }

    private fun parseLogicalOr(): Expr {
        var left = parseLogicalAnd()
        while (checkOp("||")) { advance(); left = BinaryExpr("||", left, parseLogicalAnd()) }
        return left
    }

    private fun parseLogicalAnd():
