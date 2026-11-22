package pl.cgconnector.app.domain.workout

/** Grupa mięśniowa w wyszukiwarce ćwiczeń. */
enum class ExerciseGroup {
    LEGS,
    POSTERIOR,
    CHEST,
    BACK,
    SHOULDERS,
    ARMS,
    CORE,
    FULL,
}

/**
 * Ćwiczenie z katalogu Garmina z polską nazwą. [garminName] idzie do serwera (musi być dokładnie
 * z `garminconnect.exercises`), [category] i [exerciseKey] to klucze, którymi Garmin opisuje serie
 * w zapisanym treningu.
 */
data class CatalogExercise(
    val group: ExerciseGroup,
    val polishName: String,
    val garminName: String,
    val category: String,
    val exerciseKey: String,
)

/**
 * Wybrane ćwiczenia (siłownia, biegacz, wspinacz) z polskimi nazwami. Nazwy Garmina sprawdzone
 * w `garminconnect==0.3.20` 2026-11-22; test serwera `test_android_exercise_catalog` pilnuje, żeby
 * każda była w katalogu.
 */
object ExerciseCatalog {
    val ALL: List<CatalogExercise> =
        listOf(
            CatalogExercise(ExerciseGroup.LEGS, "Przysiad ze sztangą", "Barbell Back Squat", "SQUAT", "BARBELL_BACK_SQUAT"),
            CatalogExercise(ExerciseGroup.LEGS, "Przysiad przedni ze sztangą", "Barbell Front Squat", "SQUAT", "BARBELL_FRONT_SQUAT"),
            CatalogExercise(ExerciseGroup.LEGS, "Przysiad goblet", "Goblet Squat", "SQUAT", "GOBLET_SQUAT"),
            CatalogExercise(ExerciseGroup.LEGS, "Przysiad z hantlami", "Dumbbell Squat", "SQUAT", "DUMBBELL_SQUAT"),
            CatalogExercise(ExerciseGroup.LEGS, "Przysiad ze sztangą na skrzynię", "Barbell Box Squat", "SQUAT", "BARBELL_BOX_SQUAT"),
            CatalogExercise(ExerciseGroup.LEGS, "Przysiad bułgarski z hantlami", "Dumbbell Bulgarian Split Squat", "LUNGE", "DUMBBELL_BULGARIAN_SPLIT_SQUAT"),
            CatalogExercise(ExerciseGroup.LEGS, "Przysiad bułgarski ze sztangą", "Barbell Bulgarian Split Squat", "LUNGE", "BARBELL_BULGARIAN_SPLIT_SQUAT"),
            CatalogExercise(ExerciseGroup.LEGS, "Pistolet (przysiad na jednej nodze)", "Pistol Squat", "SQUAT", "PISTOL_SQUAT"),
            CatalogExercise(ExerciseGroup.LEGS, "Przysiad z wyskokiem", "Jump Squat", "PLYO", "JUMP_SQUAT"),
            CatalogExercise(ExerciseGroup.LEGS, "Krzesełko przy ścianie", "Body-weight Wall Squat", "SQUAT", "BODY_WEIGHT_WALL_SQUAT"),
            CatalogExercise(ExerciseGroup.LEGS, "Wykrok", "Lunge", "LUNGE", "LUNGE"),
            CatalogExercise(ExerciseGroup.LEGS, "Wykroki z hantlami", "Dumbbell Lunge", "LUNGE", "DUMBBELL_LUNGE"),
            CatalogExercise(ExerciseGroup.LEGS, "Wykroki w marszu", "Walking Lunge", "LUNGE", "WALKING_LUNGE"),
            CatalogExercise(ExerciseGroup.LEGS, "Wykrok w tył z hantlami", "Dumbbell Reverse Lunge", "LUNGE", "DUMBBELL_REVERSE_LUNGE"),
            CatalogExercise(ExerciseGroup.LEGS, "Wykrok w tył ze sztangą", "Barbell Reverse Lunge", "LUNGE", "BARBELL_REVERSE_LUNGE"),
            CatalogExercise(ExerciseGroup.LEGS, "Wejścia na skrzynię", "Step-up", "SQUAT", "STEP_UP"),
            CatalogExercise(ExerciseGroup.LEGS, "Wejścia na skrzynię z hantlami", "Dumbbell Step-up", "SQUAT", "DUMBBELL_STEP_UP"),
            CatalogExercise(ExerciseGroup.LEGS, "Wskoki na skrzynię", "Box Jump", "PLYO", "BOX_JUMP"),
            CatalogExercise(ExerciseGroup.LEGS, "Wypychanie nogami (suwnica)", "Leg Press", "SQUAT", "LEG_PRESS"),
            CatalogExercise(ExerciseGroup.LEGS, "Prostowanie nóg na maszynie", "Leg Extensions", "CRUNCH", "LEG_EXTENSIONS"),
            CatalogExercise(ExerciseGroup.LEGS, "Uginanie nóg na maszynie", "Leg Curl", "LEG_CURL", "LEG_CURL"),
            CatalogExercise(ExerciseGroup.LEGS, "Wspięcia na palce", "Calf Raise", "CALF_RAISE", "CALF_RAISE"),
            CatalogExercise(ExerciseGroup.LEGS, "Wspięcia na palce stojąc", "Standing Calf Raise", "CALF_RAISE", "STANDING_CALF_RAISE"),
            CatalogExercise(ExerciseGroup.LEGS, "Wspięcia na palce siedząc", "Seated Calf Raise", "CALF_RAISE", "SEATED_CALF_RAISE"),
            CatalogExercise(ExerciseGroup.LEGS, "Wspięcia na palce na jednej nodze", "Single-leg Standing Calf Raise", "CALF_RAISE", "SINGLE_LEG_STANDING_CALF_RAISE"),
            CatalogExercise(ExerciseGroup.POSTERIOR, "Martwy ciąg", "Barbell Deadlift", "DEADLIFT", "BARBELL_DEADLIFT"),
            CatalogExercise(ExerciseGroup.POSTERIOR, "Martwy ciąg rumuński", "Romanian Deadlift", "DEADLIFT", "ROMANIAN_DEADLIFT"),
            CatalogExercise(ExerciseGroup.POSTERIOR, "Martwy ciąg rumuński na jednej nodze", "Single-leg Romanian Deadlift with Dumbbell", "DEADLIFT", "SINGLE_LEG_ROMANIAN_DEADLIFT_WITH_DUMBBELL"),
            CatalogExercise(ExerciseGroup.POSTERIOR, "Martwy ciąg sumo", "Sumo Deadlift", "DEADLIFT", "SUMO_DEADLIFT"),
            CatalogExercise(ExerciseGroup.POSTERIOR, "Martwy ciąg na trap barze", "Trap-bar Deadlift", "DEADLIFT", "TRAP_BAR_DEADLIFT"),
            CatalogExercise(ExerciseGroup.POSTERIOR, "Martwy ciąg z kettlebell", "Kettlebell Deadlift", "DEADLIFT", "KETTLEBELL_DEADLIFT"),
            CatalogExercise(ExerciseGroup.POSTERIOR, "Hip thrust ze sztangą", "Barbell Hip Thrust with Bench", "HIP_RAISE", "BARBELL_HIP_THRUST_WITH_BENCH"),
            CatalogExercise(ExerciseGroup.POSTERIOR, "Unoszenie bioder (mostek)", "Hip Raise", "HIP_RAISE", "HIP_RAISE"),
            CatalogExercise(ExerciseGroup.POSTERIOR, "Unoszenie bioder na jednej nodze", "Single-leg Hip Raise", "HIP_RAISE", "SINGLE_LEG_HIP_RAISE"),
            CatalogExercise(ExerciseGroup.POSTERIOR, "Good morning", "Good Morning", "LEG_CURL", "GOOD_MORNING"),
            CatalogExercise(ExerciseGroup.POSTERIOR, "Wyprosty tułowia (hiperekstensja)", "Hyperextension", "HYPEREXTENSION", "HYPEREXTENSION"),
            CatalogExercise(ExerciseGroup.POSTERIOR, "Superman", "Superman from Floor", "HYPEREXTENSION", "SUPERMAN_FROM_FLOOR"),
            CatalogExercise(ExerciseGroup.POSTERIOR, "Swing kettlebell", "Kettlebell Swing", "HIP_RAISE", "KETTLEBELL_SWING"),
            CatalogExercise(ExerciseGroup.CHEST, "Wyciskanie sztangi na ławce płaskiej", "Barbell Bench Press", "BENCH_PRESS", "BARBELL_BENCH_PRESS"),
            CatalogExercise(ExerciseGroup.CHEST, "Wyciskanie hantli na ławce płaskiej", "Dumbbell Bench Press", "BENCH_PRESS", "DUMBBELL_BENCH_PRESS"),
            CatalogExercise(ExerciseGroup.CHEST, "Wyciskanie sztangi na skosie", "Incline Barbell Bench Press", "BENCH_PRESS", "INCLINE_BARBELL_BENCH_PRESS"),
            CatalogExercise(ExerciseGroup.CHEST, "Wyciskanie hantli na skosie", "Incline Dumbbell Bench Press", "BENCH_PRESS", "INCLINE_DUMBBELL_BENCH_PRESS"),
            CatalogExercise(ExerciseGroup.CHEST, "Wyciskanie na maszynie Smitha", "Smith Machine Bench Press", "BENCH_PRESS", "SMITH_MACHINE_BENCH_PRESS"),
            CatalogExercise(ExerciseGroup.CHEST, "Rozpiętki z hantlami", "Dumbbell Fly", "FLYE", "DUMBBELL_FLYE"),
            CatalogExercise(ExerciseGroup.CHEST, "Pompki", "Push-up", "PUSH_UP", "PUSH_UP"),
            CatalogExercise(ExerciseGroup.CHEST, "Pompki diamentowe", "Diamond Push-up", "PUSH_UP", "DIAMOND_PUSH_UP"),
            CatalogExercise(ExerciseGroup.CHEST, "Pompki z klaśnięciem", "Clapping Push-up", "PUSH_UP", "CLAPPING_PUSH_UP"),
            CatalogExercise(ExerciseGroup.CHEST, "Pompki na poręczach (dipy)", "Body-weight Dip", "TRICEPS_EXTENSION", "BODY_WEIGHT_DIP"),
            CatalogExercise(ExerciseGroup.CHEST, "Dipy z obciążeniem", "Weighted Dip", "TRICEPS_EXTENSION", "WEIGHTED_DIP"),
            CatalogExercise(ExerciseGroup.BACK, "Podciąganie nachwytem", "Pull-up", "PULL_UP", "PULL_UP"),
            CatalogExercise(ExerciseGroup.BACK, "Podciąganie podchwytem", "Chin-up", "PULL_UP", "CHIN_UP"),
            CatalogExercise(ExerciseGroup.BACK, "Podciąganie szerokim nachwytem", "Wide-grip Pull-up", "PULL_UP", "WIDE_GRIP_PULL_UP"),
            CatalogExercise(ExerciseGroup.BACK, "Podciąganie chwytem neutralnym", "Neutral-grip Chin-up", "PULL_UP", "NEUTRAL_GRIP_CHIN_UP"),
            CatalogExercise(ExerciseGroup.BACK, "Podciąganie z obciążeniem", "Weighted Pull-up", "PULL_UP", "WEIGHTED_PULL_UP"),
            CatalogExercise(ExerciseGroup.BACK, "Podciąganie z gumą", "Band-assisted Pull-up", "PULL_UP", "BAND_ASSISTED_PULL_UP"),
            CatalogExercise(ExerciseGroup.BACK, "Ściąganie drążka wyciągu górnego", "Lat Pull-down", "PULL_UP", "LAT_PULLDOWN"),
            CatalogExercise(ExerciseGroup.BACK, "Wiosłowanie sztangą", "Barbell Row", "ROW", "BARBELL_ROW"),
            CatalogExercise(ExerciseGroup.BACK, "Wiosłowanie sztangą w opadzie", "Bent-over Row with Barbell", "ROW", "BENT_OVER_ROW_WITH_BARBELL"),
            CatalogExercise(ExerciseGroup.BACK, "Wiosłowanie hantlem", "Dumbbell Row", "ROW", "DUMBBELL_ROW"),
            CatalogExercise(ExerciseGroup.BACK, "Wiosłowanie hantlem jednorącz", "Single-arm Neutral-grip Dumbbell Row", "ROW", "SINGLE_ARM_NEUTRAL_GRIP_DUMBBELL_ROW"),
            CatalogExercise(ExerciseGroup.BACK, "Wiosłowanie siedząc na wyciągu", "Seated Cable Row", "ROW", "SEATED_CABLE_ROW"),
            CatalogExercise(ExerciseGroup.BACK, "Wiosłowanie T-bar", "T-Bar Row", "ROW", "T_BAR_ROW"),
            CatalogExercise(ExerciseGroup.BACK, "Wiosłowanie australijskie", "Inverted Row", "ROW", "INVERTED_ROW"),
            CatalogExercise(ExerciseGroup.BACK, "Wiosłowanie z kettlebell", "Kettlebell Row", "ROW", "KETTLEBELL_ROW"),
            CatalogExercise(ExerciseGroup.BACK, "Szrugsy ze sztangą", "Barbell Shrug", "SHRUG", "BARBELL_SHRUG"),
            CatalogExercise(ExerciseGroup.BACK, "Szrugsy z hantlami", "Dumbbell Shrug", "SHRUG", "DUMBBELL_SHRUG"),
            CatalogExercise(ExerciseGroup.SHOULDERS, "Wyciskanie żołnierskie", "Barbell Overhead Press", "SHOULDER_PRESS", "OVERHEAD_BARBELL_PRESS"),
            CatalogExercise(ExerciseGroup.SHOULDERS, "Wyciskanie sztangi nad głowę", "Barbell Shoulder Press", "SHOULDER_PRESS", "BARBELL_SHOULDER_PRESS"),
            CatalogExercise(ExerciseGroup.SHOULDERS, "Wyciskanie hantli nad głowę", "Dumbbell Shoulder Press", "SHOULDER_PRESS", "DUMBBELL_SHOULDER_PRESS"),
            CatalogExercise(ExerciseGroup.SHOULDERS, "Wyciskanie hantli siedząc", "Seated Dumbbell Shoulder Press", "SHOULDER_PRESS", "SEATED_DUMBBELL_SHOULDER_PRESS"),
            CatalogExercise(ExerciseGroup.SHOULDERS, "Arnoldki", "Arnold Press", "SHOULDER_PRESS", "ARNOLD_PRESS"),
            CatalogExercise(ExerciseGroup.SHOULDERS, "Wznosy bokiem", "Lateral Raise", "LATERAL_RAISE", "LATERAL_RAISE"),
            CatalogExercise(ExerciseGroup.SHOULDERS, "Wznosy bokiem z hantlami", "Dumbbell Lateral Raise", "LATERAL_RAISE", "DUMBBELL_LATERAL_RAISE"),
            CatalogExercise(ExerciseGroup.SHOULDERS, "Wznosy przodem", "Front Raise", "LATERAL_RAISE", "FRONT_RAISE"),
            CatalogExercise(ExerciseGroup.SHOULDERS, "Face pull", "Face Pull", "ROW", "FACE_PULL"),
            CatalogExercise(ExerciseGroup.SHOULDERS, "Odwrotne rozpiętki w klęku", "Kneeling Rear Fly", "FLYE", "KNEELING_REAR_FLYE"),
            CatalogExercise(ExerciseGroup.ARMS, "Uginanie ramion z hantlami", "Dumbbell Biceps Curl", "CURL", "DUMBBELL_BICEPS_CURL"),
            CatalogExercise(ExerciseGroup.ARMS, "Uginanie ramion ze sztangą", "Barbell Biceps Curl", "CURL", "BARBELL_BICEPS_CURL"),
            CatalogExercise(ExerciseGroup.ARMS, "Uginanie młotkowe", "Dumbbell Hammer Curl", "CURL", "DUMBBELL_HAMMER_CURL"),
            CatalogExercise(ExerciseGroup.ARMS, "Uginanie młotkowe na wyciągu", "Cable Hammer Curl", "CURL", "CABLE_HAMMER_CURL"),
            CatalogExercise(ExerciseGroup.ARMS, "Prostowanie ramion (triceps)", "Triceps Extension", "TRICEPS_EXTENSION", "TRICEPS_EXTENSION"),
            CatalogExercise(ExerciseGroup.ARMS, "Prostowanie ramion nad głową z hantlem", "Overhead Dumbbell Triceps Extension", "TRICEPS_EXTENSION", "OVERHEAD_DUMBBELL_TRICEPS_EXTENSION"),
            CatalogExercise(ExerciseGroup.ARMS, "Prostowanie ramion nad głową na wyciągu", "Cable Overhead Triceps Extension", "TRICEPS_EXTENSION", "CABLE_OVERHEAD_TRICEPS_EXTENSION"),
            CatalogExercise(ExerciseGroup.ARMS, "Francuskie wyciskanie (łamana)", "Lying EZ-Bar Triceps Extension", "TRICEPS_EXTENSION", "LYING_EZ_BAR_TRICEPS_EXTENSION"),
            CatalogExercise(ExerciseGroup.ARMS, "Dipy na ławce", "Bench Dip", "TRICEPS_EXTENSION", "BENCH_DIP"),
            CatalogExercise(ExerciseGroup.CORE, "Deska (plank)", "Plank", "PLANK", "PLANK"),
            CatalogExercise(ExerciseGroup.CORE, "Deska bokiem", "Side Plank", "PLANK", "SIDE_PLANK"),
            CatalogExercise(ExerciseGroup.CORE, "Brzuszki", "Crunch", "CRUNCH", "CRUNCH"),
            CatalogExercise(ExerciseGroup.CORE, "Brzuszki rowerowe", "Bicycle Crunch", "CRUNCH", "BICYCLE_CRUNCH"),
            CatalogExercise(ExerciseGroup.CORE, "Siady z leżenia", "Sit-up", "SIT_UP", "SIT_UP"),
            CatalogExercise(ExerciseGroup.CORE, "Russian twist", "Russian Twist", "CORE", "RUSSIAN_TWIST"),
            CatalogExercise(ExerciseGroup.CORE, "Unoszenie nóg w zwisie", "Hanging Leg Raise", "LEG_RAISE", "HANGING_LEG_RAISE"),
            CatalogExercise(ExerciseGroup.CORE, "Unoszenie kolan w zwisie", "Hanging Knee Raise", "LEG_RAISE", "HANGING_KNEE_RAISE"),
            CatalogExercise(ExerciseGroup.CORE, "Unoszenie nóg w leżeniu", "Leg Raise", "LEG_RAISE", "LEG_RAISE"),
            CatalogExercise(ExerciseGroup.CORE, "Palce do drążka (toes to bar)", "Toes to Bar", "CRUNCH", "TOES_TO_BAR"),
            CatalogExercise(ExerciseGroup.CORE, "L-sit w zwisie", "Hanging L Sit", "CORE", "HANGING_L_SIT"),
            CatalogExercise(ExerciseGroup.CORE, "L-sit", "L Sit", "CORE", "L_SIT"),
            CatalogExercise(ExerciseGroup.CORE, "Dead bug", "Dead Bug", "HIP_STABILITY", "DEAD_BUG"),
            CatalogExercise(ExerciseGroup.CORE, "Hollow rock", "Hollow Rock", "CRUNCH", "HOLLOW_ROCK"),
            CatalogExercise(ExerciseGroup.CORE, "Wspinaczka górska (mountain climber)", "Mountain Climber", "PLANK", "MOUNTAIN_CLIMBER"),
            CatalogExercise(ExerciseGroup.FULL, "Burpees", "Burpee", "TOTAL_BODY", "BURPEE"),
            CatalogExercise(ExerciseGroup.FULL, "Turecki wstaw", "Turkish Get-up", "CORE", "TURKISH_GET_UP"),
            CatalogExercise(ExerciseGroup.FULL, "Spacer farmera", "Farmer's Walk", "CARRY", "FARMERS_WALK"),
            CatalogExercise(ExerciseGroup.FULL, "Thrustery", "Thrusters", "SQUAT", "THRUSTERS"),
            CatalogExercise(ExerciseGroup.FULL, "Thrustery z hantlami", "Dumbbell Thrusters", "SQUAT", "DUMBBELL_THRUSTERS"),
            CatalogExercise(ExerciseGroup.FULL, "Wall ball", "Wall Ball", "SQUAT", "WALL_BALL"),
            CatalogExercise(ExerciseGroup.FULL, "Zarzut sztangi (power clean)", "Barbell Power Clean", "OLYMPIC_LIFT", "BARBELL_POWER_CLEAN"),
            CatalogExercise(ExerciseGroup.FULL, "Podrzut (clean and jerk)", "Clean and Jerk", "OLYMPIC_LIFT", "CLEAN_AND_JERK"),
            CatalogExercise(ExerciseGroup.FULL, "Rwanie", "Snatch", "OLYMPIC_LIFT", "SNATCH"),
            CatalogExercise(ExerciseGroup.FULL, "Pajacyki", "Jumping Jacks", "CARDIO", "JUMPING_JACKS"),
            CatalogExercise(ExerciseGroup.FULL, "Skakanka", "Jump Rope", "CARDIO", "JUMP_ROPE"),
            CatalogExercise(ExerciseGroup.FULL, "Wioślarz (ergometr)", "Rowing Machine", "ROW", "INDOOR_ROW"),
            CatalogExercise(ExerciseGroup.FULL, "Muscle-up na drążku", "Bar Muscle-up", "LATERAL_RAISE", "BAR_MUSCLE_UP"),
        )

    private val BY_GARMIN_NAME = ALL.associateBy { it.garminName.lowercase() }
    private val BY_KEYS = ALL.associateBy { "${it.category}/${it.exerciseKey}" }

    /** Polska nazwa dla nazwy z katalogu Garmina (dowolna wielkość liter); `null` spoza listy. */
    fun polishName(garminName: String): String? = BY_GARMIN_NAME[garminName.trim().lowercase()]?.polishName

    /** Polska nazwa dla kluczy serii z Garmin Connect (np. `SQUAT` / `BARBELL_BACK_SQUAT`). */
    fun polishNameForKeys(
        category: String?,
        exerciseKey: String?,
    ): String? = if (category == null || exerciseKey == null) null else BY_KEYS["$category/$exerciseKey"]?.polishName

    /**
     * Wyszukiwanie po polskiej i angielskiej nazwie, bez względu na wielkość liter i polskie znaki
     * („przysiad”, „przysiad sztanga”, „squat”). Wszystkie słowa zapytania muszą wystąpić.
     */
    fun search(
        query: String,
        group: ExerciseGroup? = null,
    ): List<CatalogExercise> {
        val words = normalize(query).split(' ').filter { it.isNotEmpty() }
        return ALL.filter { exercise ->
            (group == null || exercise.group == group) &&
                words.all { word ->
                    normalize(exercise.polishName).contains(word) || normalize(exercise.garminName).contains(word)
                }
        }
    }

    fun normalize(text: String): String =
        text
            .lowercase()
            .map { POLISH_LETTERS[it] ?: it }
            .joinToString("")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex(" +"), " ")
            .trim()

    private val POLISH_LETTERS =
        mapOf(
            'ą' to 'a',
            'ć' to 'c',
            'ę' to 'e',
            'ł' to 'l',
            'ń' to 'n',
            'ó' to 'o',
            'ś' to 's',
            'ź' to 'z',
            'ż' to 'z',
        )
}
