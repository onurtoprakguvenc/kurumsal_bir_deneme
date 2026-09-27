package org.yazi.motion.engines;

import org.yazi.motion.domain.ImperfectionLevel;
import org.yazi.motion.domain.RawUserPromptInput;
import org.yazi.motion.domain.ReferenceAssetBinding;
import org.yazi.motion.domain.StylePreferences;
import org.yazi.motion.domain.exception.InvalidDurationException;
import org.yazi.motion.domain.exception.InvalidPayloadException;
import org.yazi.motion.domain.exception.OutOfScopeDomainException;
import org.yazi.motion.domain.exception.PromptEngineException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Build step 04: schema check, then domain classification, then duration bounds. */
public final class UserIntentIngestionEngine implements IUserIntentIngestionEngine {

    public static final int MAX_DESCRIPTION_CHARS = 4000;

    private final DomainClassifier classifier = new DomainClassifier();

    @Override
    public CompletableFuture<ParsedIntentResult> ingestAndValidate(RawUserPromptInput input) {
        try {
            return CompletableFuture.completedFuture(process(input));
        } catch (PromptEngineException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    private ParsedIntentResult process(RawUserPromptInput input) {
        // 1. schema
        if (input == null) throw invalid("Payload must be a non-null object.", "input");
        if (input.requestId() == null || input.requestId().isBlank()) {
            throw invalid("Attribute 'request_id' is required and must be a non-empty string.", "request_id");
        }
        String description = VendorSyntax.strip(input.userDescription());
        if (description == null || description.isBlank()) {
            throw invalid("Attribute 'user_description' is required and must be a non-empty string.", "user_description");
        }
        if (description.length() > MAX_DESCRIPTION_CHARS) {
            throw invalid("Attribute 'user_description' exceeds " + MAX_DESCRIPTION_CHARS + " characters.", "user_description");
        }
        if (input.aspectRatio() == null) {
            throw invalid("Invalid or missing aspect ratio. Valid values: 16:9, 9:16, 1:1, 21:9, 4:3", "aspect_ratio");
        }
        List<ReferenceAssetBinding> assets = validateAssets(input.referenceAssets());

        // 2. domain scope
        DomainClassifier.Verdict verdict = classifier.classify(description);
        if (!verdict.videoDomain()) {
            throw new OutOfScopeDomainException("Domain boundary violation: the description " + verdict.reason() + ".",
                    Map.of("reason", verdict.reason()));
        }

        // 3. duration bounds
        double d = input.targetDurationSec();
        if (Double.isNaN(d)) throw new InvalidDurationException("Attribute 'target_duration_sec' must be a valid number.");
        if (d < TemporalContinuity.MIN_DURATION_SEC || d > TemporalContinuity.MAX_DURATION_SEC) {
            throw new InvalidDurationException(
                    "Target duration (" + d + "s) violates allowed constraints [1.0s <= t <= 60.0s].",
                    Map.of("provided_duration", d));
        }

        ImperfectionLevel level = input.imperfectionLevel() == null ? ImperfectionLevel.OFF : input.imperfectionLevel();
        // specific imperfections are used exactly as entered (whitespace trimmed, vendor tokens removed), only when enabled
        String specific = level == ImperfectionLevel.OFF || input.specificImperfections() == null
                ? "" : VendorSyntax.strip(input.specificImperfections());
        return new ParsedIntentResult(input.requestId().trim(), description, d, input.aspectRatio(), assets,
                sanitize(input.stylePreferences()), true,
                SceneAnalysis.parseSuppressAreas(input.suppressDetailAreas()), level, specific);
    }

    /** Structural problems only; temporal bounds and weights are the binder's job. */
    private static List<ReferenceAssetBinding> validateAssets(List<ReferenceAssetBinding> raw) {
        if (raw == null) return List.of();
        List<ReferenceAssetBinding> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            ReferenceAssetBinding a = raw.get(i);
            String field = "reference_assets[" + i + "]";
            if (a == null) throw invalid("Reference asset entry is null.", field);
            if (a.assetId() == null || a.assetId().isBlank()) throw invalid("Asset lacks a valid 'asset_id'.", field + ".asset_id");
            if (!ids.add(a.assetId())) throw invalid("Duplicate asset_id '" + a.assetId() + "'.", field + ".asset_id");
            if (a.uri() == null || a.uri().isBlank()) throw invalid("Asset '" + a.assetId() + "' lacks a valid 'uri'.", field + ".uri");
            try {
                if (new URI(a.uri().trim()).getScheme() == null) {
                    throw invalid("Asset '" + a.assetId() + "' uri must include a scheme (https://, file://, s3://, ...).", field + ".uri");
                }
            } catch (URISyntaxException ex) {
                throw invalid("Asset '" + a.assetId() + "' uri is not a valid URI.", field + ".uri");
            }
            out.add(a);
        }
        return out;
    }

    private static StylePreferences sanitize(StylePreferences prefs) {
        if (prefs == null) return StylePreferences.none();
        List<String> palette = prefs.colorPalette() == null ? null
                : prefs.colorPalette().stream().filter(Objects::nonNull).map(VendorSyntax::strip).toList();
        return new StylePreferences(prefs.preset(), prefs.lensMm(), VendorSyntax.strip(prefs.lightingProfile()),
                palette, prefs.atmosphericDensity(), prefs.fps());
    }

    private static InvalidPayloadException invalid(String message, String field) {
        return new InvalidPayloadException(message, Map.of("field", field));
    }
}
