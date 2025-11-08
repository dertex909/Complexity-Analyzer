package org.complexityanalyzer.graph;

import java.util.List;

public record ChemicalIngredientSlot(List<Chemical> chemicalVariants, long amount) {
    public List<Chemical> getChemicalVariants() { return chemicalVariants; }
    public long getAmount() { return amount; }
    
    public Chemical getPrimaryChemical() {
        return chemicalVariants.isEmpty() ? null : chemicalVariants.get(0);
    }
}
