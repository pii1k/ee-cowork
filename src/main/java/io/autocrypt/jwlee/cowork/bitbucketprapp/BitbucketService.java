package io.autocrypt.jwlee.cowork.bitbucketprapp;

import java.util.List;

public interface BitbucketService {
    PullRequestData fetchPullRequest(String workspace, String repository, String prId);
    void postGlobalComment(String workspace, String repository, String prId, String content);
    void postLineComment(String workspace, String repository, String prId, String filePath, int lineNumber, String content);
}

record PullRequestData(
        String id,
        String title,
        String description,
        String diff
) {}
