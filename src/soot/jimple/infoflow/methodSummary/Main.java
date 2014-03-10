package soot.jimple.infoflow.methodSummary;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.PriorityQueue;
import java.util.Stack;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Vector;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.DelayQueue;

import javax.xml.stream.XMLStreamException;

import soot.jimple.infoflow.methodSummary.util.ClassFileInformation;
import soot.jimple.infoflow.test.methodSummary.ApiClass;
import soot.jimple.infoflow.test.methodSummary.ParaToField;
import soot.jimple.infoflow.test.methodSummary.ParaToParaFlows;
import soot.jimple.infoflow.test.methodSummary.ParaToReturn;
import soot.jimple.infoflow.test.methodSummary.SimpleList;

@SuppressWarnings("unused")
class Main {
	public static void main(String[] args) throws FileNotFoundException, XMLStreamException {

		Class<?>[] droitbanchClz = { FileInputStream.class, FileOutputStream.class, IOException.class,
				HttpURLConnection.class, URL.class, ArrayList.class, InvocationTargetException.class, Method.class };

		/*
		 * { HashMap.class, FileInputStream.class, FileOutputStream.class,
		 * LinkedList.class, HashSet.class, IOException.class,
		 * HttpURLConnection.class, URL.class, ArrayList.class,
		 * InvocationTargetException.class, Method.class };
		 */
		Class<?>[] javaCollection = { HashMap.class, TreeSet.class, ArrayList.class, Stack.class, Vector.class,
				LinkedList.class, LinkedHashMap.class, ConcurrentLinkedQueue.class, PriorityQueue.class,
				ArrayBlockingQueue.class, ArrayDeque.class, ConcurrentSkipListMap.class, DelayQueue.class,
				TreeMap.class };

		Class<?>[] clazzes = {SimpleList.class};
		String[] methodFilter = {"keySet("};

		int runOption = 0;
		boolean useOutPutFolder = false;
		String outFolder = "jdkSummaries";

		String mSig = "" ;//"<soot.jimple.infoflow.test.methodSummary.FieldToPara: void listParameter5(java.util.List)>"; //<soot.jimple.infoflow.test.methodSummary.FieldToPara: void listParameter5(java.util.List)>;<soot.jimple.infoflow.test.methodSummary.FieldToPara: void listParameter5(java.util.List)>;<soot.jimple.infoflow.test.methodSummary.FieldToPara: void listParameter5(java.util.List)>;<soot.jimple.infoflow.test.methodSummary.FieldToPara: void listParameter5(java.util.List)>;<soot.jimple.infoflow.test.methodSummary.FieldToPara: void listParameter5(java.util.List)>;<soot.jimple.infoflow.test.methodSummary.FieldToPara: void listParameter5(java.util.List)>;<soot.jimple.infoflow.test.methodSummary.FieldToPara: void listParameter5(java.util.List)>;<soot.jimple.infoflow.test.methodSummary.FieldToPara: void listParameter5(java.util.List)>;<soot.jimple.infoflow.test.methodSummary.FieldToPara: void listParameter5(java.util.List)>;<soot.jimple.infoflow.test.methodSummary.FieldToPara: void listParameter5(java.util.List)>;<soot.jimple.infoflow.test.methodSummary.FieldToPara: void listParameter5(java.util.List)>;<soot.jimple.infoflow.test.methodSummary.FieldToPara: void listParameter5(java.util.List)>;<soot.jimple.infoflow.test.methodSummary.FieldToPara: void listParameter5(java.util.List)>;<soot.jimple.infoflow.test.methodSummary.FieldToPara: void listParameter5(java.util.List)>;<soot.jimple.infoflow.test.methodSummary.FieldToPara: void listParameter5(java.util.List)>;<soot.jimple.infoflow.test.methodSummary.FieldToPara: void listParameter5(java.util.List)>;<soot.jimple.infoflow.test.methodSummary.FieldToPara: void listParameter5(java.util.List)>;<soot.jimple.infoflow.test.methodSummary.FieldToPara: void listParameter5(java.util.List)>;<soot.jimple.infoflow.test.methodSummary.FieldToPara: void listParameter5(java.util.List)>;<soot.jimple.infoflow.test.methodSummary.FieldToPara: void listParameter5(java.util.List)>;<soot.jimple.infoflow.test.methodSummary.FieldToPara: void listParameter5(java.util.List)>";
		if (mSig.length() == 0) {
			for (Class<?> c : clazzes) {
				for (Method m : c.getDeclaredMethods()) {
			//		if (!m.toString().contains("$"))
						mSig = mSig + ClassFileInformation.getMethodSig(m) + ";";
				}
			}

			mSig = mSig.substring(0, mSig.length() - 1).trim();
		}
		String filter = "";
		for (String c : methodFilter) {
			filter = filter + c.toString() + ";";
		}
		if(filter != "")
			filter = filter.substring(0, filter.length() - 1);
		
		
		ArrayList<String> runArgs = new ArrayList<String>();
		runArgs.add("-m " + mSig);
		runArgs.add("-o " + runOption);
		runArgs.add("-mf " + filter);
		if (useOutPutFolder && outFolder != null && outFolder.length() > 0)
			runArgs.add("-f " + outFolder);

		System.out.println("run SummaryMain with: ");
		for (String s : runArgs) {
			System.out.println(s);
		}
		cmdSummary.main(runArgs.toArray(new String[runArgs.size()]));
	}
}