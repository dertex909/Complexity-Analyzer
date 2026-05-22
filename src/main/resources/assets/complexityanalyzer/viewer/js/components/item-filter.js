export function extractModNamespace(id) {
    const colonIdx = id.indexOf(":");
    return colonIdx >= 0 ? id.substring(0, colonIdx) : "minecraft";
}

export function passesModFilter(item, modsFilter) {
    if (!modsFilter || modsFilter.length === 0) return true;
    const modId = extractModNamespace(item.id);
    return modsFilter.includes(modId);
}

export function passesFlagsFilter(item, flagsFilter, FLAG_ENUM) {
    if (!flagsFilter || flagsFilter.length === 0) return true;

    const f = item.flags;

    const commonFlagChecks = {
        cycle: () => f & FLAG_ENUM.HAS_CYCLE,
        infinite: () => f & FLAG_ENUM.IS_INFINITE,
        recipe: () => !(f & FLAG_ENUM.HAS_RECIPE),
        hardcoded: () => f & FLAG_ENUM.IS_HARDCODED,
        protected: () => f & FLAG_ENUM.IS_PROTECTED
    };

    const mobFlagChecks = {
        boss: () => f & FLAG_ENUM.BOSS,
        miniboss: () => f & FLAG_ENUM.MINIBOSS
    };

    const flagChecks = FLAG_ENUM.BOSS ? mobFlagChecks : commonFlagChecks;

    for (const key of flagsFilter) {
        if (flagChecks[key] && flagChecks[key]()) return true;
    }

    return false;
}

export function passesCategoryFilter(item, categoriesFilter) {
    if (!categoriesFilter || categoriesFilter.length === 0) return true;
    return categoriesFilter.includes(item.categoryName || "Uncalculable");
}

export function passesRangeFilter(value, minVal, maxVal) {
    const min = minVal !== "" ? parseFloat(minVal) : -Infinity;
    const max = maxVal !== "" ? parseFloat(maxVal) : Infinity;
    return value >= min && value <= max;
}