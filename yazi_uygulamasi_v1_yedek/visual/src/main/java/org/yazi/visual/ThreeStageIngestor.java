package org.yazi.visual;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Three-slot ingestion front-end.
 * Pure deterministic local assembler — no secondary LLM calls or destructive filters.
 */
public final class ThreeStageIngestor {

    private static final Pattern ASPECT_RATIO = Pattern.compile("\\b([0-9]{1,2}:[0-9]{1,2})\\b");
    private static final Pattern AR_FLAG = Pattern.compile("--ar\\s+([0-9]{1,2}:[0-9]{1,2})", Pattern.CASE_INSENSITIVE);

    public record Directives(String lens, String elevation, String aspectRatio, List<String> engineFlags) {
        public boolean hasAspectRatio() { return aspectRatio != null && !aspectRatio.isBlank(); }
        public boolean hasEngineFlags() { return engineFlags != null && !engineFlags.isEmpty(); }
        public String engineFlagString() { return hasEngineFlags() ? String.join(" ", engineFlags) : ""; }
    }

    public record StagedScene(String midjourneyView, String fluxProse, Directives directives, List<String> notes) {
        public String pipelineText() {
            return (fluxProse == null || fluxProse.isBlank()) ? midjourneyView : fluxProse;
        }
    }

    public StagedScene stage(String slotOneRaw, String slotTwoRaw, String slotThreeRaw,
                             String modelEndpoint, String apiKey) {
        List<String> notes = new ArrayList<>();

        Directives directives = parseDirectives(slotThreeRaw, notes);

        // 1. Özne ve Eylem Önceliği (Slot 2)
        String actionBlock = cleanText(slotTwoRaw);

        // 2. Mekan ve Çevresel Katman (Slot 1)
        String envBlock = cleanText(slotOneRaw);

        // 3. Kamera Direktifleri (Slot 3)
        List<String> opticalNotes = new ArrayList<>();
        if (!directives.lens().isBlank()) opticalNotes.add(directives.lens());
        if (!directives.elevation().isBlank()) opticalNotes.add(directives.elevation());
        String cameraBlock = String.join(", ", opticalNotes);

        // Deterministik Birleştirme: Karakter/Eylem başa, Kamera ortaya, Mekan/Atmosfer sona
        StringBuilder combinedProse = new StringBuilder();
        if (!actionBlock.isBlank()) {
            combinedProse.append(actionBlock);
        }
        if (!cameraBlock.isBlank()) {
            if (!combinedProse.isEmpty()) combinedProse.append(". ");
            combinedProse.append(cameraBlock);
        }
        if (!envBlock.isBlank()) {
            if (!combinedProse.isEmpty()) combinedProse.append(". Setting: ");
            combinedProse.append(envBlock);
        }
        if (!combinedProse.isEmpty() && !combinedProse.toString().endsWith(".")) {
            combinedProse.append(".");
        }

        String assembledScene = combinedProse.toString().trim();
        notes.add("Deterministic 3-slot assembly complete. Passing intact scene to core backbone.");

        return new StagedScene(assembledScene, assembledScene, directives, List.copyOf(notes));
    }

    private Directives parseDirectives(String raw, List<String> notes) {
        if (raw == null || raw.isBlank()) {
            return new Directives("", "", "", List.of());
        }

        String lens = "";
        String elevation = "";
        String aspectRatio = "";
        List<String> engineFlags = new ArrayList<>();

        Matcher arMatcher = AR_FLAG.matcher(raw);
        if (arMatcher.find()) {
            aspectRatio = arMatcher.group(1);
        } else {
            Matcher bareAr = ASPECT_RATIO.matcher(raw);
            if (bareAr.find()) {
                aspectRatio = bareAr.group(1);
            }
        }

        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            if (trimmed.contains("--")) {
                for (String part : trimmed.split("(?=--)")) {
                    String flag = part.trim();
                    if (!flag.isEmpty() && !flag.toLowerCase().startsWith("--ar")) {
                        engineFlags.add(flag);
                    }
                }
            } else if (trimmed.toLowerCase().contains("mm") || trimmed.toLowerCase().contains("lens")) {
                lens = trimmed;
            } else if (trimmed.toLowerCase().contains("angle") || trimmed.toLowerCase().contains("elevation")
                    || trimmed.toLowerCase().contains("shot") || trimmed.toLowerCase().contains("view")) {
                elevation = trimmed;
            }
        }

        return new Directives(lens, elevation, aspectRatio, List.copyOf(engineFlags));
    }

    private String cleanText(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("\\R+", " ").replaceAll("\\s{2,}", " ").trim();
    }
}