package org.yazi.motion.json;

import org.yazi.motion.domain.PromptPackage;
import org.yazi.motion.domain.ReferenceAssetBinding;
import org.yazi.motion.domain.TemporalSegment;
import org.yazi.motion.domain.VisualStyleMatrix;
import org.yazi.motion.pipeline.PipelineResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Serializes pipeline output with the snake_case field names of the Stage-3 schema. */
public final class PromptPackageJson {
    private PromptPackageJson() {}

    public static String toJson(PipelineResult result, boolean pretty) {
        return Json.write(toMap(result), pretty);
    }

    public static String toJson(PromptPackage pkg, boolean pretty) {
        return Json.write(toMap(pkg), pretty);
    }

    public static Map<String, Object> toMap(PipelineResult result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", result.finalState());
        switch (result) {
            case PipelineResult.Success s -> {
                m.put("prompt_package", toMap(s.promptPackage()));
                if (s.focusBudget() != null) {
                    Map<String, Object> fb = new LinkedHashMap<>();
                    fb.put("source", s.focusBudget().source());
                    fb.put("focus", s.focusBudget().focus().map(Enum::name).orElse(null));
                    fb.put("suppress_detail_areas", s.focusBudget().areas());
                    fb.put("negative_terms", s.focusBudget().terms());
                    m.put("focus_budget", fb);
                }
            }
            case PipelineResult.Failure f -> {
                m.put("error_code", f.errorCode());
                m.put("exception", f.exception().name());
                m.put("message", f.message());
                m.put("details", f.details());
                m.put("timestamp", f.exception().timestamp());
                m.put("failed_at_state", f.failedAt());
            }
        }
        m.put("warnings", result.warnings());
        m.put("transitions", result.history().stream().map(t -> {
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("from", t.from());
            tm.put("to", t.to());
            if (!"ok".equals(t.note())) tm.put("note", t.note());
            return (Object) tm;
        }).toList());
        return m;
    }

    public static Map<String, Object> toMap(PromptPackage p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("request_id", p.requestId());
        m.put("schema_version", p.schemaVersion());
        m.put("target_duration", p.targetDuration());
        m.put("aspect_ratio", p.aspectRatio().label());
        m.put("style_matrix", style(p.styleMatrix()));
        m.put("temporal_segments", p.temporalSegments().stream().map(PromptPackageJson::segment).toList());
        m.put("bound_assets", p.boundAssets().stream().map(PromptPackageJson::asset).toList());
        Map<String, Object> prompts = new LinkedHashMap<>();
        prompts.put("positive_prompt_summary", p.compiledPrompts().positivePromptSummary());
        prompts.put("negative_prompt_summary", p.compiledPrompts().negativePromptSummary());
        prompts.put("temporal_breakdown_text", p.compiledPrompts().temporalBreakdownText());
        m.put("compiled_prompts", prompts);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("compiled_at", p.metadata().compiledAt());
        meta.put("engine_version", p.metadata().engineVersion());
        m.put("metadata", meta);
        return m;
    }

    private static Object style(VisualStyleMatrix s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("preset", s.preset());
        m.put("lens_mm", s.lensMm());
        m.put("lighting_profile", s.lightingProfile());
        m.put("color_palette", s.colorPalette());
        m.put("atmospheric_density", s.atmosphericDensity());
        m.put("fps", s.fps());
        return m;
    }

    private static Object segment(TemporalSegment s) {
        Map<String, Object> k = new LinkedHashMap<>();
        k.put("shot_type", s.kinematics().shotType());
        k.put("camera_angle", s.kinematics().cameraAngle());
        k.put("movement", s.kinematics().movement());
        k.put("movement_speed", s.kinematics().movementSpeed());
        k.put("focal_length_mm", s.kinematics().focalLengthMm());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("segment_index", s.segmentIndex());
        m.put("start_second", s.startSecond());
        m.put("end_second", s.endSecond());
        m.put("duration_sec", s.durationSec());
        m.put("description", s.description());
        m.put("kinematics", k);
        m.put("pacing_note", s.pacingNote());
        return m;
    }

    private static Object asset(ReferenceAssetBinding a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("asset_id", a.assetId());
        m.put("uri", a.uri());
        m.put("asset_type", a.assetType());
        m.put("spatial_position", a.spatialPosition());
        m.put("temporal_range_sec", List.of(a.temporalStartSec(), a.temporalEndSec()));
        m.put("weight", a.weight());
        return m;
    }
}
