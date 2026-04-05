package io.autocrypt.jwlee.cowork.docdiffagent;

public record TOCEntry(String title, int level, int startLine, int endLine) {
    public String getRange() {
        return startLine + "-" + endLine;
    }
}
