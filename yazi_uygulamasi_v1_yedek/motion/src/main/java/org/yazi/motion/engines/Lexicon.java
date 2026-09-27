package org.yazi.motion.engines;

import org.yazi.motion.domain.CameraAngle;
import org.yazi.motion.domain.CameraMovement;
import org.yazi.motion.domain.ShotType;
import org.yazi.motion.domain.VisualStylePreset;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Deterministic keyword tables (English + Turkish). A term ending in '*' is a prefix match,
 * which handles English inflection ("walk*" -> walks, walking) and Turkish suffixes
 * ("yağmur*" -> yağmurlu, yağmurda). Order matters: more specific phrases come first.
 */
public final class Lexicon {
    private Lexicon() {}

    private static final Map<String, Pattern> CACHE = new ConcurrentHashMap<>();

    public static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    public static boolean matches(String normalizedText, String term) {
        return pattern(term).matcher(normalizedText).find();
    }

    /** Start index of the first occurrence of the term, or -1. */
    public static int indexOf(String normalizedText, String term) {
        java.util.regex.Matcher m = pattern(term).matcher(normalizedText);
        return m.find() ? m.start() : -1;
    }

    /** Distinct matched values, ordered by where they first appear in the text. */
    public static <T> List<T> matchesInTextOrder(String normalizedText, Map<String, T> table) {
        Map<T, Integer> first = new LinkedHashMap<>();
        for (Map.Entry<String, T> e : table.entrySet()) {
            int idx = indexOf(normalizedText, e.getKey());
            if (idx >= 0) first.merge(e.getValue(), idx, Math::min);
        }
        return first.entrySet().stream().sorted(Map.Entry.comparingByValue()).map(Map.Entry::getKey).toList();
    }

    private static Pattern pattern(String term) {
        return CACHE.computeIfAbsent(term, t -> {
            boolean prefix = t.endsWith("*");
            String core = prefix ? t.substring(0, t.length() - 1) : t;
            String right = prefix ? "" : "(?![\\p{L}\\p{N}])";
            return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(core) + right);
        });
    }

    public static boolean matchesAny(String normalizedText, List<String> terms) {
        for (String t : terms) if (matches(normalizedText, t)) return true;
        return false;
    }

    public static <T> Optional<T> firstMatch(String normalizedText, Map<String, T> table) {
        for (Map.Entry<String, T> e : table.entrySet()) {
            if (matches(normalizedText, e.getKey())) return Optional.of(e.getValue());
        }
        return Optional.empty();
    }

    public static <T> List<T> allMatches(String normalizedText, Map<String, T> table) {
        List<T> out = new ArrayList<>();
        for (Map.Entry<String, T> e : table.entrySet()) {
            if (matches(normalizedText, e.getKey()) && !out.contains(e.getValue())) out.add(e.getValue());
        }
        return out;
    }

    @SafeVarargs
    private static <T> Map<String, T> table(Map.Entry<List<String>, T>... entries) {
        Map<String, T> m = new LinkedHashMap<>();
        for (var e : entries) for (String k : e.getKey()) m.put(k, e.getValue());
        return m;
    }

    private static <T> Map.Entry<List<String>, T> e(T value, String... terms) {
        return Map.entry(List.of(terms), value);
    }

    // ---------------- domain boundary ----------------

    /** Explicit requests to produce something that is not a video. */
    public static final List<Pattern> NON_VIDEO_REQUESTS = List.of(
            Pattern.compile("^(?:please\\s+|can you\\s+|could you\\s+)?(?:write|draft|compose|generate|create|give me|make)\\s+(?:me\\s+)?(?:an?\\s+|the\\s+|some\\s+)?(?:\\w+\\s+){0,2}?(?:article|essay|blog post|poem|song|lyrics|story|novel|email|letter|report|summary|code|function|program|script for (?:a |an )?(?:podcast|radio)|podcast|tweet|resume|cv|screenplay|script)s?\\b"),
            Pattern.compile("\\b(?:source code|unit tests?|sql query|regex|python (?:script|code|function)|java(?:script)? (?:code|class|function)|implement (?:a|an|the) (?:function|class|algorithm|api))\\b"),
            Pattern.compile("\\b(?:podcast episode|audio script|audiobook|radio (?:ad|script)|song lyrics|voice ?over only)\\b"),
            Pattern.compile("(?:makale|deneme yazısı|blog yazısı|şiir|şarkı sözü|hikaye|öykü|e-posta|mektup|rapor|özet|kod|program|fonksiyon|podcast)\\s*(?:yaz|oluştur|üret|hazırla)"),
            Pattern.compile("\\b(?:kaynak kod|python kodu|java kodu|sql sorgusu|sesli kitap|radyo reklamı)"),
            Pattern.compile("\\b(?:solve|calculate|compute|simplify)\\b.{0,30}\\b(?:math|equation|integral|derivative|problem|expression)s?\\b|\\b(?:denklem|matematik problemi)\\w*\\s*(?:çöz|hesapla)")
    );

    /** Explicit video framing words: their presence means the user is describing footage. */
    public static final List<String> VIDEO_FRAMING = List.of(
            "video*", "clip*", "footage", "shot*", "scene*", "camera*", "cinematic*", "film*", "b-roll",
            "trailer*", "animation*", "transition*", "montage*", "cutscene*", "geçiş*", "montaj*", "timelapse", "time-lapse", "slow motion", "frame*",
            "sahne*", "çekim*", "kamera*", "klip*", "görüntü*", "sinematik*", "animasyon*",
            "close-up", "close up", "closeup", "extreme close-up", "extreme close up", "macro", "aerial", "wide shot", "medium shot",
            "low angle", "low-angle", "high angle", "high-angle", "bird's eye", "bird's-eye", "birds eye", "dutch angle", "eye level",
            "yakın çekim", "yakın plan", "kuş bakışı", "alt açı*", "üst açı*");

    /** Concrete visual content; at least one is required for a description to be filmable. */
    public static final List<String> VISUAL_CONTENT = List.of(
            "man", "men", "woman", "women", "person", "people", "child*", "kid*", "girl*", "boy*", "crowd*",
            "character*", "hero*", "astronaut*", "soldier*", "dancer*", "robot*", "cat", "cats", "dog*", "bird*",
            "horse*", "fish", "dragon*", "wolf", "wolves", "animal*", "car", "cars", "train*", "ship*", "boat*",
            "plane*", "spaceship*", "city", "cities", "street*", "road*", "forest*", "ocean*", "sea", "beach*",
            "mountain*", "desert*", "river*", "lake*", "sky", "skies", "cloud*", "sun", "moon", "star*", "room*",
            "kitchen*", "window*", "building*", "skyline*", "bridge*", "field*", "garden*", "flower*", "tree*",
            "rain*", "snow*", "fog*", "fire*", "light*", "neon*", "sunset*", "sunrise*",
            "walk*", "run*", "fly*", "flies", "dance*", "danc*", "jump*", "drive*", "driving", "swim*", "sit*",
            "stand*", "look*", "turn*", "smil*", "cry*", "fall*", "rise*", "rising", "explod*", "pour*", "open*",
            "adam", "kadın*", "kız*", "oğlan*", "çocuk*", "insan*", "kalabalık*", "karakter*", "kedi*", "köpek*",
            "kuş*", "araba*", "tren*", "gemi*", "şehir*", "şehr*", "sokak*", "cadde*", "orman*", "okyanus*",
            "deniz*", "sahil*", "dağ*", "çöl*", "nehir*", "göl*", "gökyüzü*", "bulut*", "güneş*", "ay ışığı*",
            "oda*", "pencere*", "bina*", "köprü*", "çiçek*", "ağaç*", "yağmur*", "sis*", "ateş*", "ışık*",
            "yürü*", "koş*", "uçuyor", "uçan", "dans*", "zıpla*", "araba sür*", "yüzüyor", "yüzen", "otur*", "bak*", "dön*", "gülümse*", "ağla*");

    /** Living or moving subjects; preferred as the focus of a shot. */
    public static final List<String> ACTORS = List.of(
            "astronaut", "soldier", "dancer", "robot", "dragon", "character", "hero", "hiker", "runner", "explorer",
            "warrior", "knight", "detective", "chef", "musician", "skater", "surfer", "biker", "driver", "pilot",
            "woman", "man", "girl", "boy", "child", "person", "couple", "crowd", "cat", "dog", "bird", "horse",
            "wolf", "fox", "deer", "whale", "car", "train", "ship", "boat", "plane", "spaceship",
            "kadın", "adam", "kız", "oğlan", "çocuk", "yürüyüşçü", "dansçı", "asker", "astronot", "kedi", "köpek",
            "kuş", "araba", "tren", "gemi", "uçak");

    /** Places and landscapes; used as focus only when no actor is present. */
    public static final List<String> PLACES = List.of(
            "city", "skyline", "street", "forest", "ocean", "mountain", "valley", "desert", "river", "lake", "bridge",
            "beach", "canyon", "waterfall", "village", "castle", "room", "kitchen", "planet", "flower", "tree", "cup", "bottle", "phone", "product", "watch", "shoe",
            "şehir", "sokak", "orman", "okyanus", "deniz", "dağ", "vadi", "çöl", "nehir", "göl", "köprü", "sahil", "oda", "çiçek", "ağaç", "fincan", "şişe", "telefon", "ürün");

    /** Connectors that separate narrative beats. */
    public static final Pattern BEAT_SPLIT = Pattern.compile(
            "(?i)[.;!?]+\\s*|,?\\s+(?:and then|then|after that|afterwards|finally|meanwhile|daha sonra|ardından|sonra|en sonunda|sonunda)\\s+");

    // ---------------- cinematic cues ----------------

    /** Camera movement cues. Movements outside the vocabulary map to the closest one (orbit/drone -> TRACKING, crane down -> TILT_DOWN). */
    public static final Map<String, CameraMovement> MOVEMENT = table(
            e(CameraMovement.ZOOM_IN, "zoom in", "zooms in", "push in", "pushes in", "push-in", "dolly in", "yakınlaş*", "zoom yap*"),
            e(CameraMovement.ZOOM_OUT, "zoom out", "zooms out", "pull back", "pulls back", "dolly out", "uzaklaş*"),
            e(CameraMovement.PAN_LEFT, "pan left", "pans left", "sola pan", "sola dön*"),
            e(CameraMovement.PAN_RIGHT, "pan right", "pans right", "sağa pan", "sağa dön*"),
            e(CameraMovement.TILT_UP, "tilt up", "tilts up", "yukarı tilt", "yukarı eğil*"),
            e(CameraMovement.TILT_DOWN, "tilt down", "tilts down", "crane down", "cranes down", "descends toward", "aşağı tilt", "aşağı eğil*", "alçalarak"),
            e(CameraMovement.CRANE_UP, "crane up", "cranes up", "rises above", "vinç yukarı", "yükselerek"),
            e(CameraMovement.HANDHELD_SHAKE, "handheld", "hand-held", "shaky cam", "shaky", "el kamerası", "titrek kamera"),
            e(CameraMovement.TRACKING, "fpv", "drone*", "dron*", "orbit*", "circles around", "circling", "etrafında dön*",
                    "tracking", "tracks", "follows", "following", "follow", "takip*", "izleyen kamera"),
            e(CameraMovement.STATIC, "static shot", "locked-off", "locked off", "tripod", "sabit kamera", "sabit çekim"));

    public static final Map<String, ShotType> SHOT = table(
            e(ShotType.EXTREME_CLOSE_UP, "extreme close-up", "extreme close up", "ecu", "detay çekim*"),
            e(ShotType.MACRO, "macro", "makro"),
            e(ShotType.CLOSE_UP, "close-up", "close up", "closeup", "portrait shot", "yakın çekim", "yakın plan"),
            e(ShotType.AERIAL, "aerial", "fpv", "drone*", "dron*", "bird's eye", "birds eye", "bird's-eye", "top-down", "overhead", "havadan", "kuş bakışı"),
            e(ShotType.WIDE_SHOT, "extreme wide", "establishing shot", "panoramic", "wide shot", "wide angle", "full shot",
                    "geniş çekim", "geniş açı", "genel plan", "çok geniş", "panoramik"),
            e(ShotType.MEDIUM_SHOT, "medium shot", "mid shot", "waist-up", "orta plan", "bel plan"));

    /** Explicit camera angle cues, assigned directly to the segment that mentions them. */
    public static final Map<String, CameraAngle> ANGLE = table(
            e(CameraAngle.BIRD_EYE, "bird's eye", "bird's-eye", "birds eye", "birds-eye", "top-down", "overhead shot", "kuş bakışı", "tepeden"),
            e(CameraAngle.LOW_ANGLE, "low angle", "low-angle", "from below", "worm's eye", "alt açı*", "aşağıdan"),
            e(CameraAngle.HIGH_ANGLE, "high angle", "high-angle", "from above", "üst açı*", "yukarıdan"),
            e(CameraAngle.DUTCH_ANGLE, "dutch angle", "dutch tilt", "canted", "eğik açı*"),
            e(CameraAngle.GROUND_LEVEL, "ground level", "ground-level", "yer seviye*"),
            e(CameraAngle.EYE_LEVEL, "eye level", "eye-level", "göz hizası*"));

    // ---------------- scene tone (lighting) and energy (dynamism) ----------------

    public static final List<String> SOFT_TONE = List.of("peaceful", "serene", "calm", "tranquil", "gentle", "soft", "dreamy",
            "romantic", "tender", "quiet", "cozy", "sakin", "huzur*", "romantik", "yumuşak", "sessiz");
    public static final List<String> HARD_TONE = List.of("tense", "dark", "gritty", "ominous", "menacing", "intense", "thriller",
            "danger*", "horror", "sinister", "brutal", "explosive", "violent", "gergin", "karanlık", "gerilim*", "korku*", "tehlike*");
    public static final List<String> HIGH_ENERGY = List.of("epic", "explosive", "explosion*", "chase*", "intense", "action", "fight*",
            "battle*", "frantic", "fast", "rapid", "racing", "crash*", "sprint*", "kovalamaca", "patlama*", "hızlı", "çatışma*", "aksiyon");
    public static final List<String> LOW_ENERGY = List.of("serene", "slow", "slowly", "melanchol*", "peaceful", "calm", "tranquil",
            "quiet", "gentle", "dreamy", "lonely", "meditative", "sakin", "huzur*", "yavaş", "hüzün*", "durgun");

    // ---------------- focus budget ----------------

    public static final List<String> FOCUS_FACE = List.of("face", "faces", "facial", "eye", "eyes", "expression*", "smil*", "tear*",
            "crying", "cries", "frown*", "lips", "gaze", "gazing", "stare*", "staring", "portrait", "göz*", "gülümse*", "ifade*",
            "bakış*", "gözyaşı*");
    public static final List<String> FOCUS_MOTION = List.of("walk*", "run", "runs", "running", "dance*", "danc*", "jump*", "climb*",
            "fight*", "chase*", "sprint*", "swim*", "ride", "rides", "riding", "spin*", "leap*", "charge*", "cross", "crosses",
            "crossing", "race*", "racing", "kick*", "yürü*", "koş*", "dans*", "zıpla*", "tırman*", "kovala*");
    public static final List<String> FOCUS_OBJECT = List.of("product", "bottle", "cup", "mug", "watch", "phone", "perfume", "jewel*",
            "ring", "necklace", "shoe*", "sneaker*", "packaging", "hand", "hands", "fingers", "coffee", "glass", "ürün", "şişe",
            "fincan", "saat", "telefon", "parfüm", "el", "eller*");
    public static final List<String> FOCUS_ENVIRONMENT = List.of("landscape*", "valley", "skyline", "vista", "panorama*",
            "architecture", "scenery", "horizon", "mountains", "forest", "ocean", "coastline", "cityscape", "countryside",
            "manzara*", "vadi*", "ufuk", "orman", "okyanus");

    public static int countMatches(String normalizedText, List<String> terms) {
        int count = 0;
        for (String t : terms) if (matches(normalizedText, t)) count++;
        return count;
    }

    public static final Map<String, MotionPacing> PACING = table(
            e(MotionPacing.SPEED_RAMP, "speed ramp*", "speed-ramp*", "hız rampa*"),
            e(MotionPacing.TIMELAPSE, "timelapse", "time-lapse", "time lapse", "hızlandırılmış"),
            e(MotionPacing.SLOW_MOTION, "slow motion", "slow-motion", "slow-mo", "slowmo", "ağır çekim"),
            e(MotionPacing.FAST_PACED, "fast-paced", "fast paced", "frantic", "rapid", "high-energy", "action-packed", "hızlı tempo*", "hareketli"));

    /** Preset inference, in the Stage-3 priority order: anime > cyberpunk/neon > noir > hyperrealistic, then documentary/cinematic. */
    public static final Map<String, VisualStylePreset> PRESET = table(
            e(VisualStylePreset.ANIME, "anime", "manga", "2d animation", "cel-shaded", "cel shaded", "hand-drawn", "çizgi film"),
            e(VisualStylePreset.MVT_CYBERPUNK, "cyberpunk", "neon*", "siberpunk"),
            e(VisualStylePreset.NEO_NOIR, "noir", "neo-noir", "film noir", "kara film"),
            e(VisualStylePreset.HYPERREALISTIC_8K, "hyperrealistic", "hyper-realistic", "8k", "photorealistic", "photoreal", "ultra realistic", "gerçekçi"),
            e(VisualStylePreset.DOCUMENTARY, "documentary", "vlog", "raw footage", "belgesel*"),
            e(VisualStylePreset.CINEMATIC_35MM, "35mm", "cinematic", "film look", "sinematik"));

    public static final Map<String, String> TIME_OF_DAY = table(
            e("Neon Night", "neon", "cyberpunk", "neon ışık*"),
            e("Golden Hour", "golden hour", "sunset", "dusk", "gün batımı*", "altın saat"),
            e("Blue Hour", "blue hour", "twilight", "alacakaranlık"),
            e("Dawn", "sunrise", "dawn", "early morning", "gün doğumu*", "şafak"),
            e("Night", "night*", "midnight", "moonlight", "gece*", "ay ışığı*"),
            e("Midday", "midday", "noon", "harsh sun", "öğle*"),
            e("Overcast Day", "overcast", "cloudy", "bulutlu", "kapalı hava"));

    public static final Map<String, String> WEATHER = table(
            e("Heavy Rain", "heavy rain", "downpour", "storm*", "sağanak", "fırtına*"),
            e("Rain", "rain*", "drizzle", "yağmur*"),
            e("Snowfall", "snow*", "blizzard", "kar yağ*", "karlı"),
            e("Fog", "fog*", "mist*", "haze", "sis*", "pus*"),
            e("Dust Storm", "sandstorm", "dust", "toz*", "kum fırtına*"),
            e("Wind", "wind*", "rüzgar*"));

    public static final Map<String, String> MOOD = table(
            e("Tense", "tense", "suspense*", "thriller", "danger*", "gergin", "gerilim*"),
            e("Melancholic", "melanchol*", "sad", "lonely", "somber", "hüzün*", "melankoli*", "yalnız*"),
            e("Epic", "epic", "heroic", "majestic", "grand", "destansı", "görkemli"),
            e("Mysterious", "mysterious", "mystery", "eerie", "gizem*", "esrarengiz"),
            e("Romantic", "romantic", "romance", "love", "romantik", "aşk*"),
            e("Joyful", "joyful", "happy", "playful", "cheerful", "neşe*", "mutlu"),
            e("Calm", "calm", "peaceful", "serene", "tranquil", "sakin", "huzur*"));
}
