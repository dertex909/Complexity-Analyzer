/*
 * Complexity Analyzer
 * Copyright (C) 2026 dertex909
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.geoscan.worldgen;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.StructureAccess;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.complexityanalyzer.mixin.StructureManagerAccessor;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class StructurelessStructureManager extends StructureManager {
    private final WorldOptions worldOptions;
    private final StructureCheck structureCheck;

    public StructurelessStructureManager(StructureManager original) {
        this(((StructureManagerAccessor) original).getLevel(), ((StructureManagerAccessor) original).getWorldOptions(),
                ((StructureManagerAccessor) original).getStructureCheck());
    }

    private StructurelessStructureManager(LevelAccessor level, WorldOptions worldOptions, StructureCheck structureCheck) {
        super(level, worldOptions, structureCheck);
        this.worldOptions = worldOptions;
        this.structureCheck = structureCheck;
    }

    @Override
    public @NotNull StructureManager forWorldGenRegion(@NotNull WorldGenRegion region) {
        return new StructurelessStructureManager(region, worldOptions, structureCheck);
    }

    @Override
    public @NotNull List<StructureStart> startsForStructure(@NotNull ChunkPos chunkPos, @NotNull Predicate<Structure> predicate) {
        return Collections.emptyList();
    }

    @Override
    public @NotNull List<StructureStart> startsForStructure(@NotNull SectionPos sectionPos, @NotNull Structure structure) {
        return Collections.emptyList();
    }

    @Override
    public void fillStartsForStructure(@NotNull Structure structure, @NotNull LongSet references, @NotNull Consumer<StructureStart> consumer) {
    }

    @Override
    @Nullable
    public StructureStart getStartForStructure(@NotNull SectionPos sectionPos, @NotNull Structure structure, @NotNull StructureAccess structureAccess) {
        return StructureStart.INVALID_START;
    }

    @Override
    public void setStartForStructure(@NotNull SectionPos sectionPos, @NotNull Structure structure, @NotNull StructureStart structureStart, @NotNull StructureAccess structureAccess) {
    }

    @Override
    public void addReferenceForStructure(@NotNull SectionPos sectionPos, @NotNull Structure structure, long reference, @NotNull StructureAccess structureAccess) {
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }

    @Override
    public @NotNull StructureStart getStructureAt(@NotNull BlockPos pos, @NotNull Structure structure) {
        return StructureStart.INVALID_START;
    }

    @Override
    public @NotNull StructureStart getStructureWithPieceAt(@NotNull BlockPos pos, @NotNull TagKey<Structure> tag) {
        return StructureStart.INVALID_START;
    }

    @Override
    public @NotNull StructureStart getStructureWithPieceAt(@NotNull BlockPos pos, @NotNull HolderSet<Structure> structures) {
        return StructureStart.INVALID_START;
    }

    @Override
    public @NotNull StructureStart getStructureWithPieceAt(@NotNull BlockPos pos, @NotNull Predicate<Holder<Structure>> predicate) {
        return StructureStart.INVALID_START;
    }

    @Override
    public @NotNull StructureStart getStructureWithPieceAt(@NotNull BlockPos pos, @NotNull Structure structure) {
        return StructureStart.INVALID_START;
    }

    @Override
    public boolean structureHasPieceAt(@NotNull BlockPos pos, @NotNull StructureStart structureStart) {
        return false;
    }

    @Override
    public boolean hasAnyStructureAt(@NotNull BlockPos pos) {
        return false;
    }

    @Override
    public @NotNull Map<Structure, LongSet> getAllStructuresAt(@NotNull BlockPos pos) {
        return Collections.emptyMap();
    }

    @Override
    public @NotNull StructureCheckResult checkStructurePresence(@NotNull ChunkPos chunkPos, @NotNull Structure structure, @NotNull StructurePlacement placement, boolean skipKnownStructures) {
        return StructureCheckResult.START_NOT_PRESENT;
    }

    @Override
    public void addReference(@NotNull StructureStart structureStart) {
    }
}