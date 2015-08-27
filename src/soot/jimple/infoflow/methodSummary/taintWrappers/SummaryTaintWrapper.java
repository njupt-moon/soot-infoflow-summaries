package soot.jimple.infoflow.methodSummary.taintWrappers;

import heros.solver.IDESolver;
import heros.solver.Pair;
import heros.solver.PathEdge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import soot.ArrayType;
import soot.BooleanType;
import soot.ByteType;
import soot.CharType;
import soot.DoubleType;
import soot.FastHierarchy;
import soot.FloatType;
import soot.Hierarchy;
import soot.IntType;
import soot.Local;
import soot.LongType;
import soot.PrimType;
import soot.RefType;
import soot.Scene;
import soot.ShortType;
import soot.SootClass;
import soot.SootField;
import soot.SootMethod;
import soot.Type;
import soot.Unit;
import soot.Value;
import soot.jimple.DefinitionStmt;
import soot.jimple.InstanceInvokeExpr;
import soot.jimple.InvokeExpr;
import soot.jimple.ReturnStmt;
import soot.jimple.Stmt;
import soot.jimple.infoflow.InfoflowManager;
import soot.jimple.infoflow.data.Abstraction;
import soot.jimple.infoflow.data.AccessPath;
import soot.jimple.infoflow.data.AccessPath.ArrayTaintType;
import soot.jimple.infoflow.data.SootMethodAndClass;
import soot.jimple.infoflow.methodSummary.data.sourceSink.AbstractFlowSinkSource;
import soot.jimple.infoflow.methodSummary.data.summary.ClassSummaries;
import soot.jimple.infoflow.methodSummary.data.summary.GapDefinition;
import soot.jimple.infoflow.methodSummary.data.summary.LazySummary;
import soot.jimple.infoflow.methodSummary.data.summary.MethodFlow;
import soot.jimple.infoflow.methodSummary.data.summary.SourceSinkType;
import soot.jimple.infoflow.solver.IFollowReturnsPastSeedsHandler;
import soot.jimple.infoflow.taintWrappers.ITaintPropagationWrapper;
import soot.jimple.infoflow.util.SootMethodRepresentationParser;
import soot.jimple.infoflow.util.SystemClassHandler;
import soot.util.ConcurrentHashMultiMap;
import soot.util.MultiMap;

import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

/**
 * Taint wrapper implementation that applies method summaries created by
 * StubDroid
 * 
 * @author Steven Arzt
 *
 */
public class SummaryTaintWrapper implements ITaintPropagationWrapper {
	private InfoflowManager manager;
	private AtomicInteger wrapperHits = new AtomicInteger();
	private AtomicInteger wrapperMisses = new AtomicInteger();
	private boolean reportMissingSummaries = false;
	private ITaintPropagationWrapper fallbackWrapper = null;
	
	private LazySummary flows;
	
	private Hierarchy hierarchy;
	private FastHierarchy fastHierarchy;
	
	private MultiMap<Pair<Abstraction, SootMethod>, AccessPathPropagator> userCodeTaints
			= new ConcurrentHashMultiMap<>();
	
	protected final LoadingCache<Pair<Set<String>, String>, ClassSummaries> methodToFlows =
			IDESolver.DEFAULT_CACHE_BUILDER.build(new CacheLoader<Pair<Set<String>, String>, ClassSummaries>() {
				@Override
				public ClassSummaries load(Pair<Set<String>, String> method) throws Exception {
					final Set<String> classes = method.getO1();
					final String methodSig = method.getO2();
					
					// Get the flows in the target method
					return flows.getMethodFlows(classes, methodSig);
				}
			});
	
	/**
	 * Handler that is used for injecting taints from callbacks implemented in
	 * user code back into the summary application process
	 * 	
	 * @author Steven Arzt
	 * 
	 */
	private class SummaryFRPSHandler implements IFollowReturnsPastSeedsHandler {

		@Override
		public void handleFollowReturnsPastSeeds(Abstraction d1, Unit u,
				Abstraction d2) {
			SootMethod sm = manager.getICFG().getMethodOf(u);
			Set<AccessPathPropagator> propagators = getUserCodeTaints(d1, sm);
			if (propagators != null) {
				for (AccessPathPropagator propagator : propagators) {
					// Propagate these taints up. We leave the current gap
					AccessPathPropagator parent = safePopParent(propagator);
					GapDefinition parentGap = propagator.getParent() == null ?
							null : propagator.getParent().getGap();
					
					// Create taints from the abstractions
					Set<Taint> returnTaints = createTaintFromAccessPathOnReturn(d2.getAccessPath(),
							(Stmt) u, propagator.getGap());
					if (returnTaints == null)
						continue;
					
					// Get the correct set of flows to apply
					Set<MethodFlow> flowsInTarget = parentGap == null
							? getFlowsInOriginalCallee(propagator)
							: getFlowSummariesForGap(parentGap);
					
					// Create the new propagator, one for every taint
					Set<AccessPathPropagator> workSet = new HashSet<>();
					for (Taint returnTaint : returnTaints) {
						AccessPathPropagator newPropagator = new AccessPathPropagator(
								returnTaint, parentGap, parent,
								propagator.getParent() == null ? null : propagator.getParent().getStmt(),
								propagator.getParent() == null ? null : propagator.getParent().getD1(),
								propagator.getParent() == null ? null : propagator.getParent().getD2());
						workSet.add(newPropagator);
					}
					
					// Apply the aggregated propagators
					Set<AccessPath> resultAPs = applyFlowsIterative(flowsInTarget, new ArrayList<>(workSet));
					
					// Propagate the access paths
					if (resultAPs != null && !resultAPs.isEmpty()) {
						AccessPathPropagator rootPropagator = getOriginalCallSite(propagator);
						for (AccessPath ap : resultAPs) {
							Abstraction newAbs = rootPropagator.getD2().deriveNewAbstraction(
									ap, rootPropagator.getStmt());
							for (Unit succUnit : manager.getICFG().getSuccsOf(rootPropagator.getStmt()))
								manager.getForwardSolver().processEdge(new PathEdge<Unit, Abstraction>(
										rootPropagator.getD1(),
										succUnit,
										newAbs));
						}
					}
				}
			}
		}
		
		/**
		 * Gets the flows in the method that was originally called and from
		 * where the summary application was started
		 * @param propagator A propagator somewhere in the call tree
		 * @return The summary flows inside the original callee
		 */
		private Set<MethodFlow> getFlowsInOriginalCallee(
				AccessPathPropagator propagator) {
			Stmt originalCallSite = getOriginalCallSite(propagator).getStmt();
			
			// Get the flows in the original callee
			ClassSummaries flowsInCallee = getFlowSummariesForMethod(originalCallSite,
					originalCallSite.getInvokeExpr().getMethod());
			if (flowsInCallee.getClasses().size() != 1)
				throw new RuntimeException("Original callee must only be one method");
			
			String methodSig = originalCallSite.getInvokeExpr().getMethod().getSubSignature();
			return flowsInCallee.getAllFlowsForMethod(methodSig);
		}
		
		/**
		 * Gets the call site at which the taint application was originally
		 * started
		 * @param propagator A propagator somewhere in the call tree
		 * @return The call site at which the taint application was originally
		 * started if successful, otherwise null
		 */
		private AccessPathPropagator getOriginalCallSite(AccessPathPropagator propagator) {
			// Get the original call site
			AccessPathPropagator curProp = propagator;
			while (curProp != null) {
				if (curProp.getParent() == null)
					return curProp;
				curProp = curProp.getParent();
			}
			return null;
		}
	}
	
	/**
	 * Creates a new instance of the {@link SummaryTaintWrapper} class
	 * @param flows The flows loaded from disk
	 */
	public SummaryTaintWrapper(LazySummary flows) {
		this.flows = flows;
	}
	
	@Override
	public void initialize(InfoflowManager manager) {
		this.manager = manager;
		
		// Load all classes for which we have summaries to signatures
		for (String className : flows.getLoadableClasses())
			loadClass(className);
		for (String className : flows.getSupportedClasses())
			loadClass(className);
		
		// Get the hierarchy
		this.hierarchy = Scene.v().getActiveHierarchy();
		this.fastHierarchy = Scene.v().getOrMakeFastHierarchy();
		
		// Register the taint propagation handler
		manager.getForwardSolver().setFollowReturnsPastSeedsHandler(new SummaryFRPSHandler());
	}
	
	/**
	 * Loads the class with the given name into the scene. This makes sure that
	 * there is at least a phantom class with the given name
	 * @param className The name of the class to load
	 */
	private void loadClass(String className) {
		SootClass sc = Scene.v().getSootClassUnsafe(className);
		if (sc == null) {
			sc = new SootClass(className);
			sc.setPhantom(true);
			Scene.v().addClass(sc);
		}
		else if (sc.resolvingLevel() < SootClass.HIERARCHY)
			Scene.v().forceResolve(className, SootClass.HIERARCHY);
	}
		
	/**
	 * Creates a taint that can be propagated through method summaries based on
	 * the given access path. This method assumes that the given statement is
	 * a method call and that the access path is flowing into this call.
	 * @param ap The access path from which to create a taint
	 * @param stmt The statement at which the access path came in
	 * @param matchReturnedValues True if the method shall also match on the
	 * left side of assign statements that capture that return value of a method
	 * call, otherwise false
	 * @return The set of taints derived from the given access path
	 */
	private Set<Taint> createTaintFromAccessPathOnCall(AccessPath ap, Stmt stmt,
			boolean matchReturnedValues) {
		SootMethod sm = manager.getICFG().getMethodOf(stmt);
		Value base = getMethodBase(stmt);
		Set<Taint> newTaints = null;
		
		// Check whether the base object or some field in it is tainted
		if (!sm.isStatic()
				&& (ap.isLocal() || ap.isInstanceFieldRef())
				&& base != null
				&& base == ap.getPlainValue()) {
			if (newTaints == null)
				newTaints = new HashSet<>();
			
			newTaints.add(new Taint(SourceSinkType.Field,
					-1,
					ap.getBaseType().toString(),
					fieldArrayToStringArray(ap.getFields()),
					typeArrayToStringArray(ap.getFieldTypes()),
					ap.getTaintSubFields()));
		}
		
		// Check whether a parameter is tainted
		int paramIdx = getParameterIndex(stmt, ap);
		if (paramIdx >= 0) {
			if (newTaints == null)
				newTaints = new HashSet<>();
			
			newTaints.add(new Taint(SourceSinkType.Parameter,
					paramIdx,
					ap.getBaseType().toString(),
					fieldArrayToStringArray(ap.getFields()),
					typeArrayToStringArray(ap.getFieldTypes()),
					ap.getTaintSubFields()));
		}
		
		// If we also match returned values, we must do this here
		if (matchReturnedValues && stmt instanceof DefinitionStmt) {
			DefinitionStmt defStmt = (DefinitionStmt) stmt;
			if (defStmt.getLeftOp() == ap.getPlainValue()) {
				if (newTaints == null)
					newTaints = new HashSet<>();
				
				newTaints.add(new Taint(SourceSinkType.Return,
						-1,
						ap.getBaseType().toString(),
						fieldArrayToStringArray(ap.getFields()),
						typeArrayToStringArray(ap.getFieldTypes()),
						ap.getTaintSubFields()));
			}
		}
		
		return newTaints;
	}
	
	/**
	 * Creates a set of taints that can be propagated through method summaries
	 * based on the given access path. This method assumes that the given
	 * statement is a return site from a method.
	 * @param ap The access path from which to create a taint
	 * @param stmt The statement at which the access path came in
	 * @param gap The gap in which the taint is valid
	 * @return The taint derived from the given access path
	 */
	private Set<Taint> createTaintFromAccessPathOnReturn(AccessPath ap, Stmt stmt,
			GapDefinition gap) {
		SootMethod sm = manager.getICFG().getMethodOf(stmt);
		Set<Taint> res = null;
		
		// Check whether the base object or some field in it is tainted
		if (!sm.isStatic()
				&& (ap.isLocal() || ap.isInstanceFieldRef())
				&& ap.getPlainValue() == sm.getActiveBody().getThisLocal()) {
			if (res == null)
				res = new HashSet<>();			
			res.add(new Taint(SourceSinkType.Field,
					-1,
					ap.getBaseType().toString(),
					fieldArrayToStringArray(ap.getFields()),
					typeArrayToStringArray(ap.getFieldTypes()),
					ap.getTaintSubFields(),
					gap));
		}
		
		// Check whether a parameter is tainted
		int paramIdx = getParameterIndex(sm, ap);
		if (paramIdx >= 0) {
			if (res == null)
				res = new HashSet<>();
			res.add(new Taint(SourceSinkType.Parameter,
					paramIdx,
					ap.getBaseType().toString(),
					fieldArrayToStringArray(ap.getFields()),
					typeArrayToStringArray(ap.getFieldTypes()),
					ap.getTaintSubFields(),
					gap));
		}
		
		// Check whether the return value is tainted
		if (stmt instanceof ReturnStmt) {
			ReturnStmt retStmt = (ReturnStmt) stmt;
			if (retStmt.getOp() == ap.getPlainValue()) {
				if (res == null)
					res = new HashSet<>();
				res.add(new Taint(SourceSinkType.Return,
						-1,
						ap.getBaseType().toString(),
						fieldArrayToStringArray(ap.getFields()),
						typeArrayToStringArray(ap.getFieldTypes()),
						ap.getTaintSubFields(),
						gap));
			}
		}
		
		return res;
	}
	
	/**
	 * Converts a taint back into an access path that is valid at the given
	 * statement
	 * @param t The taint to convert into an access path
	 * @param stmt The statement at which the access path shall be valid
	 * @return The access path derived from the given taint
	 */
	private AccessPath createAccessPathFromTaint(Taint t, Stmt stmt) {
		// Convert the taints to Soot objects
		SootField[] fields = safeGetFields(t.getAccessPath());
		Type[] types = safeGetTypes(t.getAccessPathTypes());
		Type baseType = getTypeFromString(t.getBaseType());
		
		// If the taint is a return value, we taint the left side of the
		// assignment
		if (t.isReturn()) {
			// If the return value is not used, we can abort
			if (!(stmt instanceof DefinitionStmt))
				return null;
			
			DefinitionStmt defStmt = (DefinitionStmt) stmt;
			return new AccessPath(defStmt.getLeftOp(),
					fields, baseType, types, t.taintSubFields(),
					ArrayTaintType.ContentsAndLength);
		}
		
		// If the taint is a parameter value, we need to identify the
		// corresponding local
		if (t.isParameter() && stmt.containsInvokeExpr()) {
			InvokeExpr iexpr = stmt.getInvokeExpr();
			Value paramVal = iexpr.getArg(t.getParameterIndex());
			if (!AccessPath.canContainValue(paramVal))
				return null;
			
			return new AccessPath(paramVal, fields, baseType, types,
					t.taintSubFields(), ArrayTaintType.ContentsAndLength);
		}
		
		// If the taint is on the base value, we need to taint the base local
		if (t.isField()
				&& stmt.containsInvokeExpr()
				&& stmt.getInvokeExpr() instanceof InstanceInvokeExpr) {
			InstanceInvokeExpr iiexpr = (InstanceInvokeExpr) stmt.getInvokeExpr();
			return new AccessPath(iiexpr.getBase(),
					fields, baseType, types, t.taintSubFields(),
					ArrayTaintType.ContentsAndLength);
		}
		
		throw new RuntimeException("Could not convert taint to access path: "
				+ t + " at " + stmt);
	}
	
	/**
	 * Converts a taint into an access path that is valid inside a given method.
	 * This models that a taint is propagated into the method and from there on
	 * in normal IFDS.
	 * @param t The taint to convert
	 * @param sm The method in which the access path shall be created
	 * @return The access path derived from the given taint and method
	 */
	private AccessPath createAccessPathInMethod(Taint t, SootMethod sm) {
		// Convert the taints to Soot objects
		SootField[] fields = safeGetFields(t.getAccessPath());
		Type[] types = safeGetTypes(t.getAccessPathTypes());
		Type baseType = getTypeFromString(t.getBaseType());
		
		// A return value cannot be propagated into a method
		if (t.isReturn())
			throw new RuntimeException("Unsupported taint type");
		
		if (t.isParameter()) {
			Local l = sm.getActiveBody().getParameterLocal(t.getParameterIndex());
			return new AccessPath(l, fields, baseType, types, true,
					ArrayTaintType.ContentsAndLength);
		}
		
		if (t.isField() || t.isGapBaseObject()) {
			Local l = sm.getActiveBody().getThisLocal();
			return new AccessPath(l, fields, baseType, types, true,
					ArrayTaintType.ContentsAndLength);
		}
		
		throw new RuntimeException("Failed to convert taint " + t);
	}
	
	/**
	 * Converts an array of SootFields to an array of strings
	 * @param fields The array of SootFields to convert
	 * @return The array of strings corresponding to the given array of
	 * SootFields
	 */
	private String[] fieldArrayToStringArray(SootField[] fields) {
		if (fields == null)
			return null;
		String[] stringFields = new String[fields.length];
		for (int i = 0; i < fields.length; i++)
			stringFields[i] = fields[i].toString();
		return stringFields;
	}
	
	/**
	 * Converts an array of Soot Types to an array of strings
	 * @param fields The array of Soot Types to convert
	 * @return The array of strings corresponding to the given array of
	 * Soot Types
	 */
	private String[] typeArrayToStringArray(Type[] types) {
		if (types == null)
			return null;
		String[] stringTypes = new String[types.length];
		for (int i = 0; i < types.length; i++)
			stringTypes[i] = types[i].toString();
		return stringTypes;
	}
	
	@Override
	public Set<Abstraction> getTaintsForMethod(Stmt stmt, Abstraction d1,
			Abstraction taintedAbs) {
		// We only care about method invocations
		if (!stmt.containsInvokeExpr())
			return Collections.singleton(taintedAbs);
				
		// Get the cached data flows
		final SootMethod method = stmt.getInvokeExpr().getMethod();		
		ClassSummaries flowsInCallees = getFlowSummariesForMethod(
				stmt, method, taintedAbs);
		
		// If we have no data flows, we can abort early
		if (flowsInCallees.isEmpty()) {
			wrapperMisses.incrementAndGet();
			if (reportMissingSummaries 
					&& SystemClassHandler.isClassInSystemPackage(method.getDeclaringClass().getName()))
				System.out.println("Missing summary for " + method.getSignature());
			
			if (fallbackWrapper == null)
				return null;
			else {
				Set<Abstraction> fallbackTaints = fallbackWrapper.getTaintsForMethod(stmt, d1, taintedAbs);
				return fallbackTaints;
			}
		}
		wrapperHits.incrementAndGet();
		
		Set<AccessPath> res = null;
		for (String className : flowsInCallees.getClasses()) {
			// Create a level-0 propagator for the initially tainted access path
			List<AccessPathPropagator> workList = new ArrayList<AccessPathPropagator>();
			Set<Taint> taintsFromAP = createTaintFromAccessPathOnCall(
					taintedAbs.getAccessPath(), stmt, false);
			if (taintsFromAP == null || taintsFromAP.isEmpty())
				return Collections.emptySet();
			for (Taint taint : taintsFromAP)
				workList.add(new AccessPathPropagator(taint, null, null, stmt, d1, taintedAbs));
			
			// Get the flows in this class
			Set<MethodFlow> flowsInCallee = flowsInCallees.getClassSummaries(className).getAllFlows();
			
			// Apply the data flows until we reach a fixed point
			Set<AccessPath> resCallee = applyFlowsIterative(flowsInCallee, workList);
			if (resCallee != null && !resCallee.isEmpty()) {
				if (res == null)
					res = new HashSet<>();
				res.addAll(resCallee);
			}
		}
		
		// We always retain the incoming taint
		if (res == null || res.isEmpty())
			return Collections.singleton(taintedAbs);
		
		// Create abstractions from the access paths
		Set<Abstraction> resAbs = new HashSet<>(res.size() + 1);
		resAbs.add(taintedAbs);
		for (AccessPath ap : res)
			resAbs.add(taintedAbs.deriveNewAbstraction(ap, stmt));
		return resAbs;
	}
	
	/**
	 * Iteratively applies all of the given flow summaries until a fixed point
	 * is reached. if the flow enters user code, an analysis of the
	 * corresponding method will be spawned.
	 * @param flowsInCallee The flow summaries for the given callee
	 * @param workList The incoming propagators on which to apply the flow
	 * summaries
	 * @param aliasingQuery True if this query is for aliases, false if it is
	 * for normal taint propagation.
	 * @return The set of outgoing access paths
	 */
	private Set<AccessPath> applyFlowsIterative(Set<MethodFlow> flowsInCallee,
			List<AccessPathPropagator> workList) {
		Set<AccessPath> res = null;
		Set<AccessPathPropagator> doneSet = new HashSet<AccessPathPropagator>(workList);
		while (!workList.isEmpty()) {
			final AccessPathPropagator curPropagator = workList.remove(0);
			final GapDefinition curGap = curPropagator.getGap();
			
			// Make sure we don't have invalid data
			if (curGap != null && curPropagator.getParent() == null)
				throw new RuntimeException("Gap flow without parent detected");
			
			// Get the correct set of flows to apply
			Set<MethodFlow> flowsInTarget = curGap == null ? flowsInCallee
					: getFlowSummariesForGap(curGap);
			
			// If we don't have summaries for the current gap, we look for
			// implementations in the application code
			if ((flowsInTarget == null || flowsInTarget.isEmpty()) && curGap != null) {
				SootMethod callee = Scene.v().grabMethod(curGap.getSignature());
				if (callee != null)
					for (SootMethod implementor : getAllImplementors(callee))
						if (implementor.getDeclaringClass().isConcrete()
								&& !implementor.getDeclaringClass().isPhantom())
							spawnAnalysisIntoClientCode(implementor, curPropagator);
			}
			
			// Apply the flow summaries for other libraries
			if (flowsInTarget != null)
				for (MethodFlow flow : flowsInTarget) {
					if (curPropagator.isInversePropagator()) {
						// Reverse flows can only be applied if the flow is an aliasing
						// relationship
						if (!flow.isAlias())
							continue;
						
						// Reverse flows can only be applied to heap objects
						if (!canTypeAlias(flow.source().getLastFieldType()))
							continue;
						if (!canTypeAlias(flow.sink().getLastFieldType()))
							continue;
						
						// Reverse the flow if necessary
						flow = flow.reverse();
					}
									
					// Apply the flow summary
					AccessPathPropagator newPropagator = applyFlow(flow, curPropagator);
					if (newPropagator == null)
						continue;
					
					// Propagate it
					if (newPropagator.getParent() == null
							&& newPropagator.getTaint().getGap() == null) {
						AccessPath ap = createAccessPathFromTaint(newPropagator.getTaint(),
								newPropagator.getStmt());
						if (ap == null)
							continue;
						else {
							if (res == null)
								res = new HashSet<>();
							res.add(ap);
						}
					}
					if (doneSet.add(newPropagator))
						workList.add(newPropagator);
					
					// If we have have tainted a heap field, we need to look for
					// aliases as well
					if (newPropagator.getTaint().hasAccessPath()) {
						AccessPathPropagator backwardsPropagator =
								newPropagator.deriveInversePropagator();
						if (doneSet.add(backwardsPropagator))
							workList.add(backwardsPropagator);
					}
				}
		}
		return res;
	}
	
	/**
	 * Checks whether objects of the given type can have aliases
	 * @param type The type to check
	 * @return True if objects of the given type can have aliases, otherwise
	 * false
	 */
	private boolean canTypeAlias(String type) {
		Type tp = getTypeFromString(type);
		if (tp instanceof PrimType)
			return false;
		if (tp instanceof RefType)
			if (((RefType) tp).getClassName().equals("java.lang.String"))
				return false;
		return true;
	}

	/**
	 * Spawns the analysis into a gap implementation inside user code
	 * @param implementor The target method inside the user code into which the
	 * propagator shall be propagated
	 * @param propagator The implementor that gets propagated into user code
	 * @return The taints at the end of the implementor method if a summary
	 * already exists, otherwise false
	 */
	private Set<AccessPathPropagator> spawnAnalysisIntoClientCode(SootMethod implementor,
			AccessPathPropagator propagator) {
		// If the implementor has not yet been loaded, we must do this now
		if (!implementor.hasActiveBody()) {
			synchronized (implementor) {
				if (!implementor.hasActiveBody()) {
					implementor.retrieveActiveBody();
					manager.getICFG().notifyMethodChanged(implementor);
				}
			}
		}
		
		AccessPath ap = createAccessPathInMethod(propagator.getTaint(), implementor);
		Abstraction abs = new Abstraction(ap, null, null, false, false);
		
		// Create a new edge at the start point of the callee
		for (Unit sP : manager.getICFG().getStartPointsOf(implementor)) {
			PathEdge<Unit, Abstraction> edge = new PathEdge<>(abs, sP, abs);
			manager.getForwardSolver().processEdge(edge);
		}
		
		// We need to pop the last gap element off the stack
		AccessPathPropagator parent = safePopParent(propagator);
		GapDefinition gap = propagator.getParent() == null ?
				null : propagator.getParent().getGap();
		
		// We might already have a summary for the callee
		Set<AccessPathPropagator> outgoingTaints = null;
		Set<Pair<Unit, Abstraction>> endSummary = manager.getForwardSolver()
				.endSummary(implementor, abs);
		if (endSummary != null)
			// TODO: Test me
			for (Pair<Unit, Abstraction> pair : endSummary) {
				if (outgoingTaints == null)
					outgoingTaints = new HashSet<>();
				
				// Create the taint that corresponds to the access path leaving
				// the user-code method
				Set<Taint> newTaints = createTaintFromAccessPathOnReturn(
						pair.getO2().getAccessPath(),
						(Stmt) pair.getO1(),
						propagator.getGap());
				if (newTaints != null)
					for (Taint newTaint : newTaints) {
						AccessPathPropagator newPropagator = new AccessPathPropagator(
								newTaint, gap, parent,
								propagator.getParent() == null ? null : propagator.getParent().getStmt(),
								propagator.getParent() == null ? null : propagator.getParent().getD1(),
								propagator.getParent() == null ? null : propagator.getParent().getD2());
						outgoingTaints.add(newPropagator);
					}
			}
		if (outgoingTaints != null)
			return outgoingTaints;
		
		// Register the new context so that we can get the taints back
		this.userCodeTaints.put(new Pair<>(abs, implementor), propagator);
		return null;
	}

	private AccessPathPropagator safePopParent(AccessPathPropagator curPropagator) {
		if (curPropagator.getParent() == null)
			return null;
		return curPropagator.getParent().getParent();
	}
	
	/**
	 * Gets the flow summaries for the given gap definition, i.e., for the
	 * method in the gap
	 * @param gap The gap definition
	 * @return The flow summaries for the method in the given gap if they exist,
	 * otherwise null
	 */
	private Set<MethodFlow> getFlowSummariesForGap(GapDefinition gap) {
		// If we have the method in Soot, we can be more clever
		if (Scene.v().containsMethod(gap.getSignature())) {
			SootMethod gapMethod = Scene.v().getMethod(gap.getSignature());
			ClassSummaries flows = getFlowSummariesForMethod(null, gapMethod);
			if (flows != null && !flows.isEmpty())
				return flows.getAllFlows();
		}
		
		// If we don't have the method, we can only directly look for the
		// signature
		SootMethodAndClass smac = SootMethodRepresentationParser.v()
				.parseSootMethodString(gap.getSignature());
		return flows.getMethodFlows(smac.getClassName(), smac.getSubSignature());
	}
	
	/**
	 * Gets the flow summaries for the given method
	 * @param stmt (Optional) The invocation statement at which the given method
	 * is called. If this parameter is not null, it is used to find further
	 * potential callees if there are no flow summaries for the given method.
	 * @param method The method for which to get the flow summaries
	 * @return The set of flow summaries for the given method if they exist,
	 * otherwise null. Note that this is a set of sets, one set per possible
	 * callee.
	 */
	private ClassSummaries getFlowSummariesForMethod(Stmt stmt,
			final SootMethod method) {
		return getFlowSummariesForMethod(stmt, method, null);
	}
	
	/**
	 * Gets the flow summaries for the given method
	 * @param stmt (Optional) The invocation statement at which the given method
	 * is called. If this parameter is not null, it is used to find further
	 * potential callees if there are no flow summaries for the given method.
	 * @param method The method for which to get the flow summaries
	 * @param taintedAbs The tainted incoming abstraction
	 * @return The set of flow summaries for the given method if they exist,
	 * otherwise null. Note that this is a set of sets, one set per possible
	 * callee.
	 */
	private ClassSummaries getFlowSummariesForMethod(Stmt stmt,
			final SootMethod method, Abstraction taintedAbs) {
		// Check whether we have runtime type information
		if (taintedAbs != null) {
			if (stmt.getInvokeExpr() instanceof InstanceInvokeExpr) {
				InstanceInvokeExpr iinv = (InstanceInvokeExpr) stmt.getInvokeExpr();
				if (iinv.getBase() == taintedAbs.getAccessPath().getPlainValue()) {
					/*
					// Get the runtime callee
					
					
					// Get flows for the callee according to runtime types
					Set<MethodFlow> flowsInCallee = methodToFlows.getUnchecked(method);		
					if (flowsInCallee != null && !flowsInCallee.isEmpty())
						return Collections.singleton(flowsInCallee);
					*/
					
					// TODO
				}
			}
		}
		
		// Check the callgraph
		if (stmt != null) {
			Set<String> classes = new HashSet<>();
			for (SootMethod callee : manager.getICFG().getCalleesOfCallAt(stmt))
				classes.addAll(getAllChildClasses(callee.getDeclaringClass()));
			if (!classes.isEmpty()) {
				Pair<Set<String>, String> query = new Pair<>(classes, method.getSubSignature());
				ClassSummaries summaries = methodToFlows.getUnchecked(query);
				if (summaries != null && !summaries.isEmpty())
					return summaries;
			}
		}
		
		// As a last resort, check all possible callees
		SootClass targetClass = method.getDeclaringClass();
		if (stmt != null && stmt.getInvokeExpr() instanceof InstanceInvokeExpr)
			targetClass = ((RefType) ((InstanceInvokeExpr) stmt.getInvokeExpr()).getBase().getType()).getSootClass();
		Set<String> classes = getAllChildClasses(targetClass);
		Pair<Set<String>, String> query = new Pair<>(classes, method.getSubSignature());
		return methodToFlows.getUnchecked(query);
		
		// TODO: Scan up?
	}
	
	/**
	 * Gets all methods that implement the given abstract method. These are all
	 * concrete methods with the same signature in all derived classes.
	 * @param method The method for which to find implementations
	 * @return A set containing all implementations of the given method
	 */
	private Collection<SootMethod> getAllImplementors(SootMethod method) {
		final String subSig = method.getSubSignature();
		Set<SootMethod> implementors = new HashSet<SootMethod>();
		
		List<SootClass> workList = new ArrayList<SootClass>();
		workList.add(method.getDeclaringClass());
		Set<SootClass> doneSet = new HashSet<SootClass>();
		
		while (!workList.isEmpty()) {
			SootClass curClass = workList.remove(0);
			if (!doneSet.add(curClass))
				continue;
			
			if (curClass.isInterface()) {
				workList.addAll(hierarchy.getImplementersOf(curClass));
				workList.addAll(hierarchy.getSubinterfacesOf(curClass));
			}
			else
				workList.addAll(hierarchy.getSubclassesOf(curClass));
			
			SootMethod ifm = curClass.getMethodUnsafe(subSig);
			if (ifm != null)
				implementors.add(ifm);
		}
		
		return implementors;
	}
	
	/**
	 * Gets all child classes of the given class. If the given class is an
	 * interface, all implementors of this interface and its all of child-
	 * interfaces are returned.
	 * @param sc The class or interface for which to get the children
	 * @return The children of the given class or interface
	 */
	private Set<String> getAllChildClasses(SootClass sc) {
		List<SootClass> workList = new ArrayList<SootClass>();
		workList.add(sc);
		
		Set<SootClass> doneSet = new HashSet<SootClass>();
		Set<String> classes = new HashSet<>();
		
		while (!workList.isEmpty()) {
			SootClass curClass = workList.remove(0);
			if (!doneSet.add(curClass))
				continue;
			
			if (curClass.isInterface()) {
				workList.addAll(hierarchy.getImplementersOf(curClass));
				workList.addAll(hierarchy.getSubinterfacesOf(curClass));
			}
			else {
				workList.addAll(hierarchy.getSubclassesOf(curClass));
				classes.add(curClass.getName());
			}
		}
		
		return classes;
	}
	
	/**
	 * Applies a data flow summary to a given tainted access path
	 * @param flow The data flow summary to apply
	 * @param propagator The access path propagator on which to apply the given
	 * flow
	 * @return The access path propagator obtained by applying the given data
	 * flow summary to the given access path propagator. if the summary is not
	 * applicable, null is returned.
	 */
	private AccessPathPropagator applyFlow(MethodFlow flow,
			AccessPathPropagator propagator) {		
		final AbstractFlowSinkSource flowSource = flow.source();
		AbstractFlowSinkSource flowSink = flow.sink();
		final Taint taint = propagator.getTaint();
		final boolean taintSubFields = flow.sink().taintSubFields();
		
		// Make sure that the base type of the incoming taint and the one of
		// the summary are compatible
		boolean typesCompatible = isCastCompatible(
				getTypeFromString(taint.getBaseType()),
				getTypeFromString(flowSource.getBaseType()));
		if (!typesCompatible)
			return null;
		
		// If this flow starts at a gap, our current taint must be at that gap
		if (taint.getGap() != flow.source().getGap())
			return null;
		
		// Maintain the stack of access path propagations
		final AccessPathPropagator parent;
		final GapDefinition gap;
		final Stmt stmt;
		final Abstraction d1, d2;
		if (flowSink.getGap() != null) {	// ends in gap, push on stack
			parent = propagator;
			gap = flowSink.getGap();
			stmt = null;
			d1 = null;
			d2 = null;
		}
		else if (flowSource.getGap() != null) { // starts in gap, propagates inside method
			parent = propagator.getParent();
			gap = propagator.getGap();
			stmt = propagator.getStmt();
			d1 = propagator.getD1();
			d2 = propagator.getD2();
		}
		else {
			parent = safePopParent(propagator);
			gap = propagator.getParent() == null ? null : propagator.getParent().getGap();
			stmt = propagator.getParent() == null ? propagator.getStmt()
					: propagator.getParent().getStmt();
			d1 = propagator.getParent() == null ? propagator.getD1()
					: propagator.getParent().getD1();
			d2 = propagator.getParent() == null ? propagator.getD2()
					: propagator.getParent().getD2();
		}
		
		boolean addTaint = false;
		if (flowSource.isParameter() && taint.isParameter()) {
			// Get the parameter index from the call and compare it to the
			// parameter index in the flow summary
			if (taint.getParameterIndex() == flowSource.getParameterIndex()) {
				if (compareFields(taint, flowSource))
					addTaint = true;
			}
		}
		else if (flowSource.isField()) {
			// Flows from a field can either be applied to the same field or
			// the base object in total
			boolean doTaint = (taint.isGapBaseObject() || taint.isField());
			if (doTaint && compareFields(taint, flowSource))
				addTaint = true;
		}
		// We can have a flow from a local or a field
		else if (flowSource.isThis() && taint.isField())
			addTaint = true;
		// A value can also flow from the return value of a gap to somewhere
		else if (flowSource.isReturn()
				&& flowSource.getGap() != null
				&& taint.getGap() != null
				&& compareFields(taint, flowSource))
			addTaint = true;
		// For aliases, we over-approximate flows from the return edge to all
		// possible exit nodes
		else if (flowSource.isReturn()
				&& flowSource.getGap() == null
				&& taint.getGap() == null
				&& taint.isReturn()
				&& compareFields(taint, flowSource))
			addTaint = true;
		
		// If we didn't find a match, there's little we can do
		if (!addTaint)
			return null;
		
		// Construct a new propagator
		Taint newTaint = addSinkTaint(flowSource, flowSink, taint,
				propagator.getGap(), taintSubFields);
		if (newTaint == null)
			return null;
		
		AccessPathPropagator newPropagator = new AccessPathPropagator(newTaint,
				gap, parent, stmt, d1, d2);
		return newPropagator;
	}
	
	/**
	 * Checks whether the type tracked in the access path is compatible with the
	 * type of the base object expected by the flow summary
	 * @param baseType The base type tracked in the access path
	 * @param checkType The type in the summary
	 * @return True if the tracked base type is compatible with the type expected
	 * by the flow summary, otherwise false
	 */
	private boolean isCastCompatible(Type baseType, Type checkType) {
		if (baseType == Scene.v().getObjectType())
			return checkType instanceof RefType;
		if (checkType == Scene.v().getObjectType())
			return baseType instanceof RefType;
		
		return baseType == checkType
				|| fastHierarchy.canStoreType(baseType, checkType)
				|| fastHierarchy.canStoreType(checkType, baseType);
	}
	
	/**
	 * Gets the parameter index to which the given access path refers
	 * @param stmt The invocation statement
	 * @param curAP The access path
	 * @return The parameter index to which the given access path refers if it
	 * exists. Otherwise, if the given access path does not refer to a parameter,
	 * -1 is returned.
	 */
	private int getParameterIndex(Stmt stmt, AccessPath curAP) {
		if (!stmt.containsInvokeExpr())
			return -1;
		if (curAP.isStaticFieldRef())
			return -1;
		
		final InvokeExpr iexpr = stmt.getInvokeExpr();
		for (int i = 0; i < iexpr.getArgCount(); i++)
			if (iexpr.getArg(i) == curAP.getPlainValue())
				return i;
		return -1;
	}
	
	/**
	 * Gets the parameter index to which the given access path refers
	 * @param sm The method in which to check the parameter locals
	 * @param curAP The access path
	 * @return The parameter index to which the given access path refers if it
	 * exists. Otherwise, if the given access path does not refer to a parameter,
	 * -1 is returned.
	 */
	private int getParameterIndex(SootMethod sm, AccessPath curAP) {
		if (curAP.isStaticFieldRef())
			return -1;
		
		for (int i = 0; i < sm.getParameterCount(); i++)
			if (curAP.getPlainValue() == sm.getActiveBody().getParameterLocal(i))
				return i;
		return -1;
	}
	
	/**
	 * Checks whether the fields mentioned in the given taint correspond to
	 * those of the given flow source
	 * @param taintedPath The tainted access path
	 * @param flowSource The flow source with which to compare the taint
	 * @return True if the given taint references the same fields as the
	 * given flow source, otherwise false
	 */
	private boolean compareFields(Taint taintedPath,
			AbstractFlowSinkSource flowSource) {
		// If a is tainted, the summary must match a. If a.* is tainted, the
		// summary can also be a.b.
		if (taintedPath.getFieldCount() == 0)
			return !flowSource.isField() || taintedPath.taintSubFields();
		
		// if we have x.f....fn and the source is x.f'.f1'...f'n+1 and we don't
		// taint sub, we can't have a match
		if (taintedPath.getFieldCount() < flowSource.getAccessPathLength()
				&& !taintedPath.taintSubFields())
			return false;
		
		// Compare the shared sub-path
		for (int i = 0; i < taintedPath.getFieldCount()
				&& i < flowSource.getAccessPathLength(); i++) {
			String taintField = taintedPath.getAccessPath()[i];
			String sourceField = flowSource.getAccessPath()[i];
			if (!sourceField.equals(taintField))
				return false;
		}

		return true;
	}

	/**
	 * Gets the field with the specified signature if it exists, otherwise
	 * returns null
	 * 
	 * @param fieldSig
	 *            The signature of the field to retrieve
	 * @return The field with the given signature if it exists, otherwise null
	 */
	private SootField safeGetField(String fieldSig) {
		if (fieldSig == null || fieldSig.equals(""))
			return null;
		
		SootField sf = Scene.v().grabField(fieldSig);
		if (sf != null)
			return sf;
		
		// This field does not exist, so we need to create it
		String className = fieldSig.substring(1);
		className = className.substring(0, className.indexOf(":"));
		SootClass sc = Scene.v().getSootClassUnsafe(className);
		if (sc.resolvingLevel() < SootClass.SIGNATURES
				&& !sc.isPhantom()) {
			System.err.println("WARNING: Class not loaded: " + sc);
			return null;
		}
		
		String type = fieldSig.substring(fieldSig.indexOf(": ") + 2);
		type = type.substring(0, type.indexOf(" "));
		
		String fieldName = fieldSig.substring(fieldSig.lastIndexOf(" ") + 1);
		fieldName = fieldName.substring(0, fieldName.length() - 1);
		
		return Scene.v().makeFieldRef(sc, fieldName, getTypeFromString(type), false).resolve();
	}
	
	/**
	 * Creates a Soot Type from the given string
	 * @param type A string representing a Soot type
	 * @return The Soot Type corresponding to the given string
	 */
	private Type getTypeFromString(String type) {		
		// Reduce arrays
		int numDimensions = 0;
		while (type.endsWith("[]")) {
			numDimensions++;
			type = type.substring(0, type.length() - 2);
		}
		
		// Generate the target type
		final Type t;
		if (type.equals("int"))
			t = IntType.v();
		else if (type.equals("long"))
			t = LongType.v();
		else if (type.equals("float"))
			t = FloatType.v();
		else if (type.equals("double"))
			t = DoubleType.v();
		else if (type.equals("boolean"))
			t = BooleanType.v();
		else if (type.equals("char"))
			t = CharType.v();
		else if (type.equals("short"))
			t = ShortType.v();
		else if (type.equals("byte"))
			t = ByteType.v();
		else
			t = RefType.v(type);
		
		if (numDimensions == 0)
			return t;
		return ArrayType.v(t, numDimensions);
	}

	/**
	 * Gets an array of fields with the specified signatures
	 * 
	 * @param fieldSigs
	 *            , list of the field signatures to retrieve
	 * @return The Array of fields with the given signature if all exists,
	 *         otherwise null
	 */
	private SootField[] safeGetFields(String[] fieldSigs) {
		if (fieldSigs == null || fieldSigs.length == 0)
			return null;
		SootField[] fields = new SootField[fieldSigs.length];
		for (int i = 0; i < fieldSigs.length; i++) {
			fields[i] = safeGetField(fieldSigs[i]);
			if (fields[i] == null)
				return null;
		}
		return fields;
	}

	/**
	 * Gets an array of types with the specified class names
	 * 
	 * @param fieldTypes
	 *            , list of the type names to retrieve
	 * @return The Array of fields with the given signature if all exists,
	 *         otherwise null
	 */
	private Type[] safeGetTypes(String[] fieldTypes) {
		if (fieldTypes == null || fieldTypes.length == 0)
			return null;
		Type[] types = new Type[fieldTypes.length];
		for (int i = 0; i < fieldTypes.length; i++)
			types[i] = getTypeFromString(fieldTypes[i]);
		return types;
	}
	
	/**
	 * Given the taint at the source and the flow, computes the taint at the
	 * sink
	 * @param flowSource The source definition of the flow
	 * @param flowSink The sink definition of the flow
	 * @param taint The taint at the source statement
	 * @param gap The gap at which the new flow will hold
	 * @param taintSubFields True if all sub-fields of the sink shall be tainted
	 * as well, otherwise false
	 * @return The taint at the sink that is obtained when applying the given
	 * flow to the given source taint
	 */
	private Taint addSinkTaint(AbstractFlowSinkSource flowSource,
			AbstractFlowSinkSource flowSink, Taint taint, GapDefinition gap,
			boolean taintSubFields) {
		final String[] remainingFields = getRemainingFields(flowSource, taint);
		final String[] remainingFieldTypes = getRemainingFieldTypes(flowSource, taint);

		final String[] appendedFields = append(flowSink.getAccessPath(), remainingFields);
		final String[] appendedFieldTypes = append(flowSink.getAccessPathTypes(), remainingFieldTypes);
		
		// If we taint something in the base object, its type must match. We
		// might have a taint for "a" in o.add(a) and need to check whether
		// "o" matches the expected type in our summary.
		int lastCommonAPIdx = Math.min(flowSource.getAccessPathLength(), taint.getAccessPathLength());
		Type sinkType = getTypeFromString(getAssignmentType(flowSink));
		Type taintType = getTypeFromString(getAssignmentType(taint, lastCommonAPIdx - 1));
		if (!isCastCompatible(taintType, sinkType)) {
			// If the target is an array, the value might also flow into an element
			Type sinkBaseType = sinkType;
			boolean found = false;
			while (sinkBaseType instanceof ArrayType) {
				sinkBaseType = ((ArrayType) sinkBaseType).getElementType(); 
				if (isCastCompatible(taintType, sinkBaseType)) {
					found = true;
					break;
				}
			}
			if (!found)
				return null;
		}
		
		// If we enter a gap with a type "GapBaseObject", we need to convert
		// it to a regular field
		SourceSinkType sourceSinkType = flowSink.getType();
		if (flowSink.getType() == SourceSinkType.GapBaseObject
				&& remainingFields != null && remainingFields.length > 0)
			sourceSinkType = SourceSinkType.Field;
		
		// Taint the correct fields
		return new Taint(sourceSinkType,
				flowSink.getParameterIndex(),
				flowSink.getBaseType(),
				appendedFields,
				appendedFieldTypes,
				taintSubFields || taint.taintSubFields(),
				gap);
	}
	
	/**
	 * Gets the type at the given position from a taint.
	 * @param taint The taint from which to get the propagation type
	 * @param idx The index inside the access path from which to get the type.
	 * -1 refers to the base type
	 * @return The type at the given index inside the access path
	 */
	private String getAssignmentType(Taint taint, int idx) {
		if (idx < 0)
			return taint.getBaseType();
		return taint.getAccessPathTypes()[idx];
	}
	
	/**
	 * Gets the type that is finally assigned when propagating this source or
	 * sink. For an access path a.b.c, this would be the type of "c".
	 * @param srcSink The source or sink from which to get the propagation type
	 * @return The type of the value which the access path of the given source
	 * or sink finally references
	 */
	private String getAssignmentType(AbstractFlowSinkSource srcSink) {
		if (!srcSink.hasAccessPath())
			return srcSink.getBaseType();
		return srcSink.getAccessPathTypes()[srcSink.getAccessPathLength() - 1];
	}
	
	/**
	 * Concatenates the two given arrays to one bigger array
	 * @param fields The first array
	 * @param remainingFields The second array
	 * @return The concatenated array containing all elements from both given
	 * arrays
	 */
	private String[] append(String[] fields, String[] remainingFields) {
		if (fields == null)
			return remainingFields;
		if (remainingFields == null)
			return fields;
		
		int cnt = fields.length + remainingFields.length;
		String[] appended = new String[cnt];
		System.arraycopy(fields, 0, appended, 0, fields.length);
		System.arraycopy(remainingFields, 0, appended, fields.length, remainingFields.length);
		return appended;
	}
	
	/**
	 * Gets the remaining fields which are tainted, but not covered by the given
	 * flow summary source
	 * @param flowSource The flow summary source
	 * @param taintedPath The tainted access path
	 * @return The remaining fields which are tainted in the given access path,
	 * but which are not covered by the given flow summary source
	 */
	private String[] getRemainingFields(AbstractFlowSinkSource flowSource,
			Taint taintedPath) {
		if (!flowSource.hasAccessPath())
			return taintedPath.getAccessPath();
		
		int fieldCnt = taintedPath.getFieldCount() - flowSource.getAccessPathLength();
		if (fieldCnt <= 0)
			return null;
		
		String[] fields = new String[fieldCnt];
		System.arraycopy(taintedPath.getAccessPath(), flowSource.getAccessPathLength(),
				fields, 0, fieldCnt);
		return fields;
	}
	
	/**
	 * Gets the types of the remaining fields which are tainted, but not covered
	 * by the given flow summary source
	 * @param flowSource The flow summary source
	 * @param taintedPath The tainted access path
	 * @return The types of the remaining fields which are tainted in the given
	 * access path, but which are not covered by the given flow summary source
	 */
	private String[] getRemainingFieldTypes(AbstractFlowSinkSource flowSource,
			Taint taintedPath) {
		if (!flowSource.hasAccessPath())
			return taintedPath.getAccessPathTypes();
		
		int fieldCnt = taintedPath.getFieldCount() - flowSource.getAccessPathLength();
		if (fieldCnt <= 0)
			return null;
		
		String[] fields = new String[fieldCnt];
		System.arraycopy(taintedPath.getAccessPathTypes(), flowSource.getAccessPathLength(),
				fields, 0, fieldCnt);
		return fields;
	}
	
	/**
	 * Gets the base object on which the method is invoked
	 * 
	 * @param stmt
	 *            The statement for which to get the base of the method call
	 * @return The base object of the method call if it exists, otherwise null
	 */
	private Value getMethodBase(Stmt stmt) {
		if (!stmt.containsInvokeExpr())
			throw new RuntimeException("Statement is not a method call: "
					+ stmt);
		InvokeExpr invExpr = stmt.getInvokeExpr();
		if (invExpr instanceof InstanceInvokeExpr)
			return ((InstanceInvokeExpr) invExpr).getBase();
		return null;
	}

	@Override
	public boolean isExclusive(Stmt stmt, Abstraction taintedPath) {
		// If we support the method, we are exclusive for it
		return supportsCallee(stmt) || (fallbackWrapper != null
				&& fallbackWrapper.isExclusive(stmt, taintedPath)) ;
	}
	
	@Override
	public boolean supportsCallee(SootMethod method) {
		// Check whether we directly support that class
		if (flows.supportsClass(method.getDeclaringClass().getName()))
			return true;
		
		return false;
	}
	
	@Override
	public boolean supportsCallee(Stmt callSite) {
		if (!callSite.containsInvokeExpr())
			return false;

		SootMethod method = callSite.getInvokeExpr().getMethod();
		return supportsCallee(method);
	}
	
	/**
	 * Gets the propagators that have been registered as being passed into user
	 * code with the given context and for the given callee
	 * @param abs The context abstraction with which the taint was passed into
	 * the callee
	 * @param callee The callee into which the taint was passed
	 * @return The of taint propagators passed into the given callee with the
	 * given context. If no such propagators exist, null is returned.
	 */
	Set<AccessPathPropagator> getUserCodeTaints(Abstraction abs, SootMethod callee) {
		return this.userCodeTaints.get(new Pair<>(abs, callee));
	}

	@Override
	public int getWrapperHits() {
		return wrapperHits.get();
	}

	@Override
	public int getWrapperMisses() {
		return wrapperMisses.get();
	}

	@Override
	public Set<Abstraction> getAliasesForMethod(Stmt stmt, Abstraction d1,
			Abstraction taintedAbs) {
		// We only care about method invocations
		if (!stmt.containsInvokeExpr())
			return Collections.singleton(taintedAbs);
		
		// Get the cached data flows
		final SootMethod method = stmt.getInvokeExpr().getMethod();
		ClassSummaries flowsInCallees = getFlowSummariesForMethod(stmt, method);
		
		// If we have no data flows, we can abort early
		if (flowsInCallees.isEmpty()) {
			if (fallbackWrapper == null)
				return null;
			else
				return fallbackWrapper.getAliasesForMethod(stmt, d1, taintedAbs);
		}
		
		Set<AccessPath> res = null;
		for (String className : flowsInCallees.getClasses()) {
			// Create a level-0 propagator for the initially tainted access path
			List<AccessPathPropagator> workList = new ArrayList<AccessPathPropagator>();
			Set<Taint> taintsFromAP = createTaintFromAccessPathOnCall(
					taintedAbs.getAccessPath(), stmt, true);
			if (taintsFromAP == null || taintsFromAP.isEmpty())
				return Collections.emptySet();
			for (Taint taint : taintsFromAP)
				workList.add(new AccessPathPropagator(taint, null, null, stmt, d1,
						taintedAbs, true));
			
			// Get the flows in this class
			Set<MethodFlow> flowsInCallee = flowsInCallees.getClassSummaries(className).getAllFlows();
			
			// Apply the data flows until we reach a fixed point
			Set<AccessPath> resCallee = applyFlowsIterative(flowsInCallee, workList);
			if (resCallee != null && !resCallee.isEmpty()) {
				if (res == null)
					res = new HashSet<>();
				res.addAll(resCallee);
			}
		}
		
		// We always retain the incoming taint
		if (res == null || res.isEmpty())
			return Collections.singleton(taintedAbs);
		
		// Create abstractions from the access paths
		Set<Abstraction> resAbs = new HashSet<>(res.size() + 1);
		resAbs.add(taintedAbs);
		for (AccessPath ap : res)
			resAbs.add(taintedAbs.deriveNewAbstraction(ap, stmt));
		return resAbs;
	}
	
	/**
	 * Sets whether missing summaries for classes shall be reported on the
	 * command-line
	 * @param report True if missing summaries shall be reported on the command
	 * line, otherwise false
	 */
	public void setReportMissingDummaries(boolean report) {
		this.reportMissingSummaries = report;
	}
	
	/**
	 * Sets the fallback taint wrapper to be used if there is no StubDroid summary
	 * for a certain class
	 * @param fallbackWrapper The fallback taint wrapper to be used if there is no
	 * StubDroid summary for a certain class
	 */
	public void setFallbackTaintWrapper(ITaintPropagationWrapper fallbackWrapper) {
		this.fallbackWrapper = fallbackWrapper;
	}

}
