package soot.jimple.infoflow.methodSummary.data;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import soot.jimple.infoflow.util.ConcurrentHashSet;

/**
 * Data class encapsulating a set of method summaries
 * 
 * @author Steven Arzt
 */
public class MethodSummaries {
	
	private final Map<String, Set<AbstractMethodFlow>> flows;
	
	public MethodSummaries() {
		this.flows = new ConcurrentHashMap<String, Set<AbstractMethodFlow>>();
	}
	
	public MethodSummaries(Map<String, Set<AbstractMethodFlow>> flows) {
		this.flows = flows;
	}
	
	/**
	 * Merges the given flows into the this method summary object
	 * @param newFlows The new flows to be merged
	 */
	public void merge(Map<String, Set<AbstractMethodFlow>> newFlows) {
		for (String key : newFlows.keySet()) {
			Set<AbstractMethodFlow> existingFlows = flows.get(key);
			if (existingFlows != null)
				existingFlows.addAll(newFlows.get(key));
			else
				flows.put(key, newFlows.get(key));
		}
	}

	/**
	 * Merges the given flows into the this method summary object
	 * @param newFlows The new flows to be merged
	 */
	public void merge(MethodSummaries newFlows) {
		merge(newFlows.flows);
	}
	
	/**
	 * Gets all flows for the method with the given signature
	 * @param methodSig The signature of the method for which to retrieve the
	 * data flows
	 * @return The set of data flows for the method with the given signature
	 */
	public Set<AbstractMethodFlow> getFlowsForMethod(String methodSig) {
		Set<AbstractMethodFlow> methFlows = flows.get(methodSig);
		if (methFlows == null)
			return Collections.emptySet();
		return methFlows;
	}
	
	/**
	 * Adds a new flow for a method to this summary object
	 * @param methodSig The signature of the method for which to add the flow
	 * @param flow The flow to add
	 */
	public void addFlowForMethod(String methodSig, AbstractMethodFlow flow) {
		Set<AbstractMethodFlow> methodFlows = flows.get(methodSig);
		if (methodFlows == null) {
			methodFlows = new ConcurrentHashSet<AbstractMethodFlow>();
			flows.put(methodSig, methodFlows);
		}
		methodFlows.add(flow);
	}
	
	/**
	 * Adds new flows for a method to this summary object
	 * @param methodSig The signature of the method for which to add the flows
	 * @param flow The flows to add
	 */
	public void addFlowForMethod(String methodSig, Set<AbstractMethodFlow> newFlows) {
		Set<AbstractMethodFlow> methodFlows = flows.get(methodSig);
		if (methodFlows == null) {
			methodFlows = new ConcurrentHashSet<AbstractMethodFlow>();
			flows.put(methodSig, methodFlows);
		}
		methodFlows.addAll(newFlows);
	}

	public Map<String, Set<AbstractMethodFlow>> getFlows() {
		return this.flows;
	}

}