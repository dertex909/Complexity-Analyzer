package org.complexityanalyzer.bytecode;

import it.unimi.dsi.fastutil.objects.*;
import org.complexityanalyzer.ComplexityAnalyzer;
import org.complexityanalyzer.bytecode.model.*;

public final class SemanticGraphBuilder {

    private SemanticGraphBuilder() {
    }

    public static ObjectList<SemanticEdge> build(
            ObjectList<EventNode> events,
            ObjectList<MachineNode> machines) {

        var edges = new ObjectArrayList<SemanticEdge>();

        for (var event : events) {
            for (var action : event.actions()) {
                var sa = SemanticMapper.mapAction(action);
                var builder = new SemanticEdge.Builder()
                        .from("ACTION:" + event.eventType())
                        .to("Item:" + SemanticMapper.normalizeItemId(action.target()).toUpperCase())
                        .action(sa.category())
                        .weight(ConfidenceModel.DIRECT_CALL)
                        .source(SemanticEdge.EdgeSource.DIRECT_CALL)
                        .putContext("count", sa.count())
                        .putContext("method", event.methodName())
                        .putContext("class", event.className());

                for (var cond : event.conditions()) {
                    var sc = SemanticMapper.mapCondition(cond);
                    builder.addCondition(sc.humanReadable());
                }

                edges.add(builder.build());
            }
        }

        for (var machine : machines) {
            for (var input : machine.inputItems()) {
                String inId = SemanticMapper.normalizeItemId(input);
                for (var output : machine.outputItems()) {
                    String outId = SemanticMapper.normalizeItemId(output);
                    double w = machine.deterministic()
                            ? ConfidenceModel.DIRECT_CALL : ConfidenceModel.HEURISTIC;
                    var src = machine.deterministic()
                            ? SemanticEdge.EdgeSource.DIRECT_CALL
                            : SemanticEdge.EdgeSource.HEURISTIC;

                    edges.add(new SemanticEdge.Builder()
                            .from("Item:" + inId.toUpperCase())
                            .to("Item:" + outId.toUpperCase())
                            .action("MACHINE_PROCESS")
                            .weight(w)
                            .source(src)
                            .putContext("machine", machine.className())
                            .putContext("mod", machine.modId())
                            .build());
                }
            }
        }

        ComplexityAnalyzer.LOGGER.info("[SemanticGraphBuilder] {} edges from {} events + {} machines",
                edges.size(), events.size(), machines.size());

        return edges;
    }
}
