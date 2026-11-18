package com.ds.ide.core;

import java.util.regex.Pattern;

/**
 * Redacts API keys, bearer tokens, and secret query parameters from core stdout/stderr
 * lines before they are written to {@code files/logs/core.log} or Android {@code logcat}.
 */
public final class CoreLogRedactor {

    private static final Pattern OPENROUTER_KEY =
            Pattern.compile("\\bsk-or-v1-[A-Za-z0-9_-]{8,}");
    private static final Pattern SK_KEY =
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{8,}");
    private static final Pattern GOOGLE_AI_KEY =
            Pattern.compile("\\bAIza[A-Za-z0-9_-]{20,}");
    private static final Pattern GROQ_KEY =
            Pattern.compile("\\bgsk_[A-Za-z0-9_-]{8,}");
    private static final Pattern HF_KEY =
            Pattern.compile("\\bhf_[A-Za-z0-9_-]{8,}");
    private static final Pattern XAI_KEY =
            Pattern.compile("\\bxai-[A-Za-z0-9_-]{8,}");
    private static final Pattern NVAPI_KEY =
            Pattern.compile("\\bnvapi-[A-Za-z0-9_-]{8,}");
    private static final Pattern GITHUB_PAT =
            Pattern.compile("\\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{12,}|\\bgithub_pat_[A-Za-z0-9_]{16,}");
    private static final Pattern OPENCODE_KEY =
            Pattern.compile("\\boc_sk_[A-Za-z0-9_-]{8,}");
    private static final Pattern OTHER_PREFIX_KEY =
            Pattern.compile("\\b(thk_[a-z]+_|abl_|coa_)[A-Za-z0-9_-]{8,}");
    private static final Pattern BEARER_TOKEN =
            Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{12,}");
    private static final Pattern URL_TOKEN_PARAM =
            Pattern.compile("(?i)([?&](?:token|api_?key|access_token|refresh_token|key)=)[^&\\s\"']+");
    private static final Pattern API_KEY_HEADER =
            Pattern.compile("(?i)((?:x-api-key|api-key)\\s*[:=]\\s*)[^\\s\",}]+");

    private CoreLogRedactor() {
    }

    public static String redact(String line) {
        if (line == null || line.length() < 10) {
            return line;
        }
        String out = line;
        try {
            out = OPENROUTER_KEY.matcher(out).replaceAll("sk-or-v1-[REDACTED]");
            out = SK_KEY.matcher(out).replaceAll("sk-[REDACTED]");
            out = GOOGLE_AI_KEY.matcher(out).replaceAll("AIza[REDACTED]");
            out = GROQ_KEY.matcher(out).replaceAll("gsk_[REDACTED]");
            out = HF_KEY.matcher(out).replaceAll("hf_[REDACTED]");
            out = XAI_KEY.matcher(out).replaceAll("xai-[REDACTED]");
            out = NVAPI_KEY.matcher(out).replaceAll("nvapi-[REDACTED]");
            out = GITHUB_PAT.matcher(out).replaceAll("gh_[REDACTED]");
            out = OPENCODE_KEY.matcher(out).replaceAll("oc_sk_[REDACTED]");
            out = OTHER_PREFIX_KEY.matcher(out).replaceAll("$1[REDACTED]");
            out = BEARER_TOKEN.matcher(out).replaceAll("$1[REDACTED]");
            out = URL_TOKEN_PARAM.matcher(out).replaceAll("$1[REDACTED]");
            out = API_KEY_HEADER.matcher(out).replaceAll("$1[REDACTED]");
        } catch (Throwable ignored) {
            return out;
        }
        return out;
    }
}
