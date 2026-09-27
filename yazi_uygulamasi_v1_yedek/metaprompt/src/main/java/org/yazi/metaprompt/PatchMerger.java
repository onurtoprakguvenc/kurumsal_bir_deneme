package org.yazi.metaprompt;

import org.yazi.metaprompt.MetaPromptContracts.CompiledPromptSpec;
import org.yazi.metaprompt.MetaPromptContracts.PromptVerificationReport;

import java.util.List;

/**
 * "Apply patches", ported from prompt_gelistirme's web UI ({@code applyPatches()} in index.html) with the same
 * behaviour: the hardening patches become a numbered "## Hardened Operational Directives" block, appended to the
 * Stage 2 assembled prompt and injected before the first {@code </system_instruction>} of the deployment
 * manifest (or appended when that tag is missing).
 */
public final class PatchMerger {

    private PatchMerger() {}

    static final String CLOSING_TAG = "</system_instruction>";

    public record Merged(CompiledPromptSpec spec, PromptVerificationReport report) {}

    public static Merged apply(CompiledPromptSpec spec, PromptVerificationReport report) {
        List<String> patches = report.hardeningPatches();
        if (patches == null || patches.isEmpty()) {
            return new Merged(spec, report);
        }
        StringBuilder block = new StringBuilder("\n\n## Hardened Operational Directives\n");
        for (int i = 0; i < patches.size(); i++) {
            if (i > 0) {
                block.append('\n');
            }
            block.append(i + 1).append(". ").append(patches.get(i));
        }
        String patchBlock = block.toString();

        CompiledPromptSpec patchedSpec = new CompiledPromptSpec(spec.systemDirective(), spec.behavioralConstraints(),
                spec.inputSchema(), spec.outputSchema(), spec.edgeCaseHandling(),
                nullToEmpty(spec.assembledPrompt()) + patchBlock);

        String manifest = report.deploymentManifest();
        PromptVerificationReport patchedReport = report;
        if (manifest != null && !manifest.isEmpty()) {
            int at = manifest.indexOf(CLOSING_TAG);
            String merged = (at >= 0)
                    ? manifest.substring(0, at) + "\n" + patchBlock + "\n" + manifest.substring(at)
                    : manifest + "\n" + patchBlock;
            patchedReport = new PromptVerificationReport(report.structuralIntegrityScore(), report.syntheticTestInput(),
                    report.simulatedOutputDigest(), report.boundaryBreachRisks(), report.hardeningPatches(), merged);
        }
        return new Merged(patchedSpec, patchedReport);
    }

    private static String nullToEmpty(String s) {
        return (s == null) ? "" : s;
    }
}
