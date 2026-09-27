# Porting record

Where each part of this app came from, and what changed on the way. The original projects in
`~/IdeaProjects` were only read, never modified.

## motion ← video_uretme_prompt (verbatim)

- All 54 source files under `org/example/videoprompt` and all 5 test files, copied byte-for-byte.
- The only edit: the package name `org.example.videoprompt` → `org.yazi.motion` (package, import and javadoc lines).
- Not ported: `org.example.Main` (CLI; also held a hardcoded API key), `org.example.server.PromptServer`
  (web server bound to 0.0.0.0 with CORS `*`), `static/index.html`.
- The original 51 tests run unchanged in the new module.
- Connection to the app: `desktop/CompileActions.compileVideo` builds a `RawUserPromptInput` from the
  selection and runs `PromptPipelineOrchestrator` locally (no model call, 0 tokens).

Re-check (Git Bash, from the repo root):

```bash
VS=~/IdeaProjects/video_uretme_prompt/src
cd motion && find src -name "*.java" | while read -r f; do rel=${f#src/}
  cmp -s <(sed -b 's/org\.yazi\.motion/org.example.videoprompt/g' "$f") \
         "$VS/${rel/org\/yazi\/motion/org/example/videoprompt}" || echo "DIFFERS: $rel"; done
```

## visual ← image_generate_prompt_improve (port + bug fixes)

Verbatim (package line only: `org.example` → `org.yazi.visual`):
`SceneContract.java` (records and the hand-written Gemini schema), `PromptCompiler.java`,
`ThreeStageIngestor.java`.

Rewritten shell, same wording:

| New | Old | Kept word-for-word | Changed |
|---|---|---|---|
| `NarrativeExtractor` | `NarrativeExtractor` | system instruction, temperature 0.4, max 2000 tokens, error texts | transport → shared gateway; also reports token usage |
| `VisualPipeline` | `VisualPromptApp.handleApiCompile` + `streamStructuredContract` | contract system instruction, `"Raw scene draft to structure:\n"`, temperature 0.2, max 4000 tokens, the three ingestion modes, slot-3 and inline `--ar` overrides, log messages | see fixes |
| `GeminiModel`, `IngestionMode` | nested enums of `VisualPromptApp` | names, model ids, labels | moved to top-level files |

Fixes:

1. **Shared state.** `regionalPassesEnabled`, `activeModel` and `activeEngine` were fields of one instance
   shared by a 4-thread web server; `handleApiCompile` swapped `regionalPassesEnabled` around each call, so
   concurrent requests could receive each other's schema. Settings now travel in `VisualRequest`, and the
   schema is built per call. Covered by a 200-request concurrency test.
2. **Ignored model.** The web request's `model` only reached the keyframe step; the contract extraction always
   used the `activeModel` field. Every call now uses `VisualRequest.model()`. Covered by a test.

Removed with the server: reading any `.txt` path named in a request, server-side clipboard copy, CORS `*`,
the CLI loop, the hardcoded API key, `index.html`.

## prose ← writing_improve_v2 (refactor, general-purpose only)

Architecture refactored and bugs fixed (see the class javadocs). Fiction-specific parts
(`SkeletonCompiler`, `SkeletalState`, `AcousticVoiceContract`, fiction examples in the style contract) are
intentionally not part of this editor. Sampling: continuation 0.35–0.45, rewrite 0.35, ask 0.25.

## metaprompt ← prompt_gelistirme (port + Java enforcement)

Ported from the working copy of `PromptCompilerServer.java` as it was on disk (it had uncommitted edits).

Verbatim, byte-for-byte, in `MetaPromptContracts.java` between the `VERBATIM BEGIN/END` markers:

- lines 47–95: the records `StructuralPayload`, `PromptIntentDigest`, `CompiledPromptSpec`,
  `PromptVerificationRequest`, `PromptVerificationReport`;
- lines 102–296: `STAGE1_SYSTEM`, `STAGE2_SYSTEM`, `STAGE3_SYSTEM` and the three response schemas.

Left out: the HTTP-only records `Stage1Request` and `ErrorResponse`, the HTTP server and handlers, CORS,
the hardcoded key (it also went into the URL query string), `index.html`.

`MetaPromptPipeline` keeps the user-message wording of stages 2 and 3, temperature 0.1, 8192 output tokens,
`NON_NULL` serialisation of stage inputs, the original validation messages, the score clamp and the lenient
JSON reading (now in the shared gateway). The Stage 3 input is built as the web UI built it
(`assembledPrompt`, `inputSchema`, optional sample payload, Stage 1 `requiredInputs`).

`PatchMerger` is a direct port of the web UI's `applyPatches()`.

Enforced in Java (previously only requested in the prompts):

- `PlaceholderNormalizer`: the Stage 3 token rule (`"source text"` → `{{SOURCE_TEXT}}`, `"user-query"` →
  `{{USER_QUERY}}`, `"schema.def"` → `{{SCHEMADEF}}`, empty → `{{INPUT}}`). After Stage 3 the manifest's
  `<dynamic_input>` block is rebuilt from `requiredInputs` (one `name: {{TOKEN}}` line each) and any
  non-conforming tokens the model wrote are reported. Non-ASCII letters are stripped as the rule says, so a
  Turkish name like "ürün adı" becomes `{{RN_AD}}`; transliterating first would be a one-line change.
- `PersonaLinter`: sentences opening with "Act as", "You are a/an", "You're a/an", "Assume the role of",
  "Take on the role of" or "Adopt the persona of" are removed from the Stage 2 directive, constraints,
  edge-case handling and assembled prompt, from the Stage 3 hardening patches, and from the manifest's
  `<system_instruction>`. Mid-sentence and quoted mentions are left alone.

Re-check the verbatim blocks (Git Bash, from the repo root):

```bash
SRC=~/IdeaProjects/prompt_gelistirme/src/main/java/org/example/PromptCompilerServer.java
F=metaprompt/src/main/java/org/yazi/metaprompt/MetaPromptContracts.java
s1=$(grep -n "lines 47-95)" $F | cut -d: -f1); s2=$(grep -n "lines 102-296)" $F | cut -d: -f1)
cmp <(sed -b -n "$((s1+2)),$((s1+50))p" $F) <(sed -b -n '47,95p' $SRC) && echo records OK
cmp <(sed -b -n "$((s2+2)),$((s2+196))p" $F) <(sed -b -n '102,296p' $SRC) && echo prompts OK
```
