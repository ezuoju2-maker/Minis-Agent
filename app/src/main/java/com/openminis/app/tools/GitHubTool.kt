package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam

object GitHubTool {

    const val NAME = "github"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "GitHub connector. Once the user's token is connected you act with ALL of its scopes — repos, " +
            "files, issues, pull requests, releases, Actions (dispatch/rerun), orgs, packages, gists, search, " +
            "GraphQL — not a read-only subset.\n\nACTIONS: `status` (who is connected, token kind, scopes, rate " +
            "limit); `login` (store `token` — a classic PAT or fine-grained token); `logout`; `api` (any REST " +
            "call: `method` + `path` + optional JSON `body`); `graphql` (POST /graphql with `query`).\n\nUSE IT " +
            "FOR: inspecting repos the user names, reading/creating files, opening or merging PRs, filing and " +
            "closing issues, triggering or rerunning workflows, cutting releases, searching code/issues, and " +
            "anything else the REST/GraphQL API can do. Do not guess state you can query.\n\nSAFETY: deleting a " +
            "repository (`DELETE /repos/owner/name`) needs `confirm=true` and an explicit user request; never " +
            "push credentials into a repo (the secret scanner will block it anyway). Anyone without a token can " +
            "still call public endpoints but only 60 requests/hour — for real work, ask the user to connect a " +
            "token whose scopes cover what they want done.",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary shown to the user. Use the same language as the user.",
            ),
            "action" to AgentToolParam(
                "string",
                "\"status\" (default), \"login\", \"logout\", \"api\", \"graphql\".",
                enumValues = listOf("status", "login", "logout", "api", "graphql"),
            ),
            "token" to AgentToolParam(
                "string",
                "login only. A GitHub token (classic PAT or fine-grained). Classic PATs expose their scopes; " +
                    "fine-grained tokens hide them but work the same. Ask the user to paste it; never echo it back.",
            ),
            "method" to AgentToolParam(
                "string",
                "api only. GET, POST, PATCH, PUT or DELETE.",
                enumValues = listOf("GET", "POST", "PATCH", "PUT", "DELETE"),
            ),
            "path" to AgentToolParam(
                "string",
                "api only. REST path starting with /, e.g. /repos/owner/name/contents/README.md or " +
                    "/search/code?q=filename:SKILL.md.",
            ),
            "body" to AgentToolParam(
                "string",
                "api only, optional. JSON request body as a string (quotes must be escaped).",
            ),
            "query" to AgentToolParam(
                "string",
                "graphql only. The GraphQL query (and variables folded into it); sent as {\"query\": …}.",
            ),
            "confirm" to AgentToolParam(
                "boolean",
                "api only. Set true ONLY when the user explicitly asked to delete a repository.",
            ),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "action", "token", "method", "path", "body", "query", "confirm"),
    )
}
