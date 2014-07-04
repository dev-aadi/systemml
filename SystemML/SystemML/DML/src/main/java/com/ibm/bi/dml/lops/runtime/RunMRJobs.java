/**
 * IBM Confidential
 * OCO Source Materials
 * (C) Copyright IBM Corp. 2010, 2014
 * The source code for this program is not published or otherwise divested of its trade secrets, irrespective of what has been deposited with the U.S. Copyright Office.
 */

package com.ibm.bi.dml.lops.runtime;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.SequenceFile;

import com.ibm.bi.dml.api.DMLScript;
import com.ibm.bi.dml.api.DMLScript.RUNTIME_PLATFORM;
import com.ibm.bi.dml.conf.ConfigurationManager;
import com.ibm.bi.dml.conf.DMLConfig;
import com.ibm.bi.dml.hops.OptimizerUtils;
import com.ibm.bi.dml.lops.Lop;
import com.ibm.bi.dml.lops.compile.JobType;
import com.ibm.bi.dml.lops.compile.Recompiler;
import com.ibm.bi.dml.parser.DMLTranslator;
import com.ibm.bi.dml.parser.Expression.DataType;
import com.ibm.bi.dml.parser.Expression.ValueType;
import com.ibm.bi.dml.runtime.DMLRuntimeException;
import com.ibm.bi.dml.runtime.DMLUnsupportedOperationException;
import com.ibm.bi.dml.runtime.controlprogram.ExecutionContext;
import com.ibm.bi.dml.runtime.controlprogram.LocalVariableMap;
import com.ibm.bi.dml.runtime.controlprogram.caching.MatrixObject;
import com.ibm.bi.dml.runtime.instructions.MRInstructionParser;
import com.ibm.bi.dml.runtime.instructions.MRJobInstruction;
import com.ibm.bi.dml.runtime.instructions.CPInstructions.Data;
import com.ibm.bi.dml.runtime.instructions.CPInstructions.ScalarObject;
import com.ibm.bi.dml.runtime.instructions.MRInstructions.DataGenMRInstruction;
import com.ibm.bi.dml.runtime.instructions.MRInstructions.RandInstruction;
import com.ibm.bi.dml.runtime.instructions.MRInstructions.ReblockInstruction;
import com.ibm.bi.dml.runtime.instructions.MRInstructions.SeqInstruction;
import com.ibm.bi.dml.runtime.matrix.CMCOVMR;
import com.ibm.bi.dml.runtime.matrix.CSVReblockMR;
import com.ibm.bi.dml.runtime.matrix.CombineMR;
import com.ibm.bi.dml.runtime.matrix.DataGenMR;
import com.ibm.bi.dml.runtime.matrix.DataPartitionMR;
import com.ibm.bi.dml.runtime.matrix.GMR;
import com.ibm.bi.dml.runtime.matrix.GroupedAggMR;
import com.ibm.bi.dml.runtime.matrix.JobReturn;
import com.ibm.bi.dml.runtime.matrix.MMCJMR;
import com.ibm.bi.dml.runtime.matrix.MMRJMR;
import com.ibm.bi.dml.runtime.matrix.MatrixCharacteristics;
import com.ibm.bi.dml.runtime.matrix.MatrixDimensionsMetaData;
import com.ibm.bi.dml.runtime.matrix.MatrixFormatMetaData;
import com.ibm.bi.dml.runtime.matrix.ReblockMR;
import com.ibm.bi.dml.runtime.matrix.SortMR;
import com.ibm.bi.dml.runtime.matrix.WriteCSVMR;
import com.ibm.bi.dml.runtime.matrix.io.MatrixBlock;
import com.ibm.bi.dml.runtime.matrix.io.MatrixCell;
import com.ibm.bi.dml.runtime.matrix.io.MatrixIndexes;
import com.ibm.bi.dml.runtime.matrix.io.OutputInfo;
import com.ibm.bi.dml.runtime.util.MapReduceTool;
import com.ibm.bi.dml.utils.Statistics;


public class RunMRJobs 
{
	@SuppressWarnings("unused")
	private static final String _COPYRIGHT = "Licensed Materials - Property of IBM\n(C) Copyright IBM Corp. 2010, 2014\n" +
                                             "US Government Users Restricted Rights - Use, duplication  disclosure restricted by GSA ADP Schedule Contract with IBM Corp.";
	
	public static boolean flagLocalModeOpt = false;
	public enum ExecMode { LOCAL, CLUSTER, INVALID }; 
	//private static final Log LOG = LogFactory.getLog(RunMRJobs.class.getName());

	public static JobReturn submitJob(MRJobInstruction inst, ExecutionContext ec ) 
		throws DMLRuntimeException 
	{
		
		JobReturn ret = new JobReturn();

		// Obtain references to all input matrices 
		MatrixObject[] inputMatrices = inst.extractInputMatrices(ec);
		
		// export dirty matrices to HDFS
		// note: for REBLOCK postponed until we know if necessary
		if( !(inst.getJobType() == JobType.REBLOCK) )
		{
			//export matrices
			for(MatrixObject m : inputMatrices) {
				if ( m.isDirty() )
					m.exportData();
			}

			//check input files
			checkEmptyInputs( inst, inputMatrices );	
		}
		
		// Obtain references to all output matrices
		MatrixObject[] outputMatrices = inst.extractOutputMatrices(ec);
		
		boolean execCP = false;
		
		// Spawn MapReduce Jobs
		try {
			// replace all placeholders in all instructions with appropriate values
			String rdInst = updateLabels(inst.getIv_randInstructions(), ec.getVariables());
			String rrInst = updateLabels(inst.getIv_recordReaderInstructions(), ec.getVariables());
			String mapInst = updateLabels(inst.getIv_instructionsInMapper(), ec.getVariables());
			String shuffleInst = updateLabels(inst.getIv_shuffleInstructions(), ec.getVariables());
			String aggInst = updateLabels(inst.getIv_aggInstructions(), ec.getVariables());
			String otherInst = updateLabels(inst.getIv_otherInstructions(), ec.getVariables());
			boolean jvmReuse = ConfigurationManager.getConfig().getBooleanValue(DMLConfig.JVM_REUSE);
			
			switch(inst.getJobType()) {
			
			case GMR: 
				ret = GMR.runJob(inst, inst.getInputs(), inst.getInputInfos(), 
						inst.getRlens(), inst.getClens(), inst.getBrlens(), inst.getBclens(),
						inst.getPartitioned(), inst.getPformats(), inst.getPsizes(),
						rrInst, mapInst, aggInst, otherInst,
						inst.getIv_numReducers(), inst.getIv_replication(), jvmReuse, inst.getIv_resultIndices(), inst.getDimsUnknownFilePrefix(),
						inst.getOutputs(), inst.getOutputInfos() );
				 break;

			case DATAGEN:
				if(    OptimizerUtils.ALLOW_DYN_RECOMPILATION
					&& OptimizerUtils.ALLOW_RAND_JOB_RECOMPILE
					&& DMLScript.rtplatform != RUNTIME_PLATFORM.HADOOP 
					&& Recompiler.checkCPDataGen( inst, rdInst ) ) 
				{
					ret = executeInMemoryDataGenOperations(inst, rdInst, outputMatrices);
					Statistics.decrementNoOfExecutedMRJobs();
					execCP = true;
				}
				else 
				{
					ret = DataGenMR.runJob(inst, 
							rdInst.split(Lop.INSTRUCTION_DELIMITOR), mapInst, aggInst, otherInst, 
							inst.getIv_numReducers(), inst.getIv_replication(), inst.getIv_resultIndices(), inst.getDimsUnknownFilePrefix(),
							inst.getOutputs(), inst.getOutputInfos());
				}
				break;
			
			case CM_COV:
				ret = CMCOVMR.runJob(inst, inst.getInputs(),  inst.getInputInfos(), 
						inst.getRlens(), inst.getClens(), inst.getBrlens(), inst.getBclens(),
						mapInst, shuffleInst, 
						inst.getIv_numReducers(), inst.getIv_replication(), inst.getIv_resultIndices(), 
						inst.getOutputs(), inst.getOutputInfos() );
				break;
			
			case GROUPED_AGG:
				ret = GroupedAggMR.runJob(inst, inst.getInputs(),  inst.getInputInfos(), 
						inst.getRlens(), inst.getClens(), inst.getBrlens(), inst.getBclens(),
						shuffleInst, otherInst, 
						inst.getIv_numReducers(), inst.getIv_replication(), inst.getIv_resultIndices(), inst.getDimsUnknownFilePrefix(),  
						inst.getOutputs(), inst.getOutputInfos() );
				break;
			
			case REBLOCK:
			case CSV_REBLOCK:
				if(    OptimizerUtils.ALLOW_DYN_RECOMPILATION 
					&& DMLScript.rtplatform != RUNTIME_PLATFORM.HADOOP 
					&& Recompiler.checkCPReblock( inst, inputMatrices ) ) 
				{
					ret = executeInMemoryReblockOperations(inst, shuffleInst, inputMatrices, outputMatrices);
					Statistics.decrementNoOfExecutedMRJobs();
					execCP = true;
				}
				else 
				{
					// export dirty matrices to HDFS (initially deferred)
					for(MatrixObject m : inputMatrices) {
						if ( m.isDirty() )
							m.exportData();
					}
					checkEmptyInputs( inst, inputMatrices );
					
					if ( inst.getJobType() == JobType.REBLOCK ) {
						ret = ReblockMR.runJob(inst, inst.getInputs(),  inst.getInputInfos(), 
								inst.getRlens(), inst.getClens(), inst.getBrlens(), inst.getBclens(), getNNZ(inputMatrices),
								mapInst, shuffleInst, otherInst,
								inst.getIv_numReducers(), inst.getIv_replication(), jvmReuse, inst.getIv_resultIndices(),   
								inst.getOutputs(), inst.getOutputInfos() );
					}
					else if( inst.getJobType() == JobType.CSV_REBLOCK ) {
						ret = CSVReblockMR.runJob(inst, inst.getInputs(), inst.getInputInfos(), 
								inst.getRlens(), inst.getClens(), inst.getBrlens(), inst.getBclens(),
								shuffleInst, otherInst,
								inst.getIv_numReducers(), inst.getIv_replication(), inst.getIv_resultIndices(),   
								inst.getOutputs(), inst.getOutputInfos() );
					}
				}
				break;

			case CSV_WRITE:
				ret = WriteCSVMR.runJob(inst, inst.getInputs(), inst.getInputInfos(), 
						inst.getRlens(), inst.getClens(), inst.getBclens(), inst.getBclens(), shuffleInst,
						inst.getIv_numReducers(), inst.getIv_replication(), inst.getIv_resultIndices(), inst.getOutputs());
				break;
				
			case MMCJ:
				ret = MMCJMR.runJob(inst, inst.getInputs(),  inst.getInputInfos(), 
						inst.getRlens(), inst.getClens(), inst.getBrlens(), inst.getBclens(),
						mapInst, aggInst, shuffleInst,
						inst.getIv_numReducers(), inst.getIv_replication(),    
						inst.getOutputs()[0], inst.getOutputInfos()[0] );
				break;

			case MMRJ:
				ret = MMRJMR.runJob(inst, inst.getInputs(),  inst.getInputInfos(), 
						inst.getRlens(), inst.getClens(), inst.getBrlens(), inst.getBclens(),
						mapInst, aggInst, shuffleInst, otherInst,
						inst.getIv_numReducers(), inst.getIv_replication(), inst.getIv_resultIndices(),    
						inst.getOutputs(), inst.getOutputInfos() );
				break;

			case SORT:
				boolean weightsflag = true;
				if ( !mapInst.equalsIgnoreCase("") )
					weightsflag = false;
				ret = SortMR.runJob(inst, inst.getInputs()[0],  inst.getInputInfos()[0], 
						inst.getRlens()[0], inst.getClens()[0], inst.getBrlens()[0], inst.getBclens()[0],
						mapInst, 
						inst.getIv_numReducers(), inst.getIv_replication(),    
						inst.getOutputs()[0], inst.getOutputInfos()[0], weightsflag );
				break;

			case COMBINE:
				ret = CombineMR.runJob(inst, inst.getInputs(),  inst.getInputInfos(), 
						inst.getRlens(), inst.getClens(), inst.getBrlens(), inst.getBclens(),
						shuffleInst, 
						inst.getIv_numReducers(), inst.getIv_replication(), inst.getIv_resultIndices(),    
						inst.getOutputs(), inst.getOutputInfos() );
				break;
			
			case DATA_PARTITION:
				ret = DataPartitionMR.runJob(inst, inputMatrices, shuffleInst, inst.getIv_resultIndices(), outputMatrices, inst.getIv_numReducers(), inst.getIv_replication());
				break;
				
			default:
				throw new DMLRuntimeException("Invalid jobtype: " + inst.getJobType());
			}
			
		} // end of try block
		catch (Exception e) {
			throw new DMLRuntimeException( e );
		}

		if (ret.checkReturnStatus()) {
			/*
			 * Check if any output is empty. If yes, create a dummy file. Needs
			 * to be done only in case of (1) CellOutputInfo and if not CP, or 
			 * (2) BinaryBlockOutputInfo if not CP and output empty blocks disabled.
			 */
			try {
				if( !execCP )
				{
					for (int i = 0; i < outputMatrices.length; i++) {
						//get output meta data
						MatrixFormatMetaData meta = (MatrixFormatMetaData)outputMatrices[i].getMetaData();
						MatrixCharacteristics mc = meta.getMatrixCharacteristics();
						OutputInfo outinfo = meta.getOutputInfo();
						String fname = outputMatrices[i].getFileName();
						
						if (MapReduceTool.isHDFSFileEmpty(fname)) 
						{
							//prepare output file
							Path filepath = new Path(fname, "0-m-00000");
							
							if (outinfo == OutputInfo.TextCellOutputInfo) {
								FileSystem fs = FileSystem.get(ConfigurationManager.getCachedJobConf());
								FSDataOutputStream writer = fs.create(filepath);
								writer.writeBytes("1 1 0");
								writer.close();
							} 
							else if (outinfo == OutputInfo.BinaryCellOutputInfo) {
								Configuration conf = new Configuration();
								FileSystem fs = FileSystem.get(conf);
								SequenceFile.Writer writer = new SequenceFile.Writer(fs, conf, filepath,
										                        MatrixIndexes.class, MatrixCell.class);
								MatrixIndexes index = new MatrixIndexes(1, 1);
								MatrixCell cell = new MatrixCell(0);
								writer.append(index, cell);
								writer.close();
							}
							else if( outinfo == OutputInfo.BinaryBlockOutputInfo ){
								Configuration conf = new Configuration();
								FileSystem fs = FileSystem.get(conf);
								SequenceFile.Writer writer = new SequenceFile.Writer(fs, conf, filepath,
										                        MatrixIndexes.class, MatrixBlock.class);
								MatrixIndexes index = new MatrixIndexes(1, 1);
								MatrixBlock block = new MatrixBlock((int)Math.min(mc.get_rows(), mc.get_rows_per_block()),
																	(int)Math.min(mc.get_cols(), mc.get_cols_per_block()), true);
								writer.append(index, block);
								writer.close();
							}
						}
						
						outputMatrices[i].setFileExists(true);
						
						if ( inst.getJobType() != JobType.CSV_WRITE ) {
							// write out metadata file
							// Currently, valueType information in not stored in MR instruction, 
							// since only DOUBLE matrices are supported ==> hard coded the value type information for now
							MapReduceTool.writeMetaDataFile(fname + ".mtd", ValueType.DOUBLE,  ((MatrixDimensionsMetaData)ret.getMetaData(i)).getMatrixCharacteristics(), outinfo);
						}
					}
				}
				return ret;
			} catch (IOException e) {
				throw new DMLRuntimeException(e);
			}
		}

		// should not come here!
		throw new DMLRuntimeException("Unexpected Job Type: " + inst.getJobType());
	}

	/**
	 * 
	 * @param inst
	 * @param inputMatrices
	 * @param pb
	 * @throws DMLRuntimeException
	 */
	private static void checkEmptyInputs( MRJobInstruction inst, MatrixObject[] inputMatrices ) 
		throws DMLRuntimeException
	{
		// Check if any of the input files are empty.. only for those job types
		// for which empty inputs are NOT allowed
		if (!inst.getJobType().areEmptyInputsAllowed()) {
			for ( int i=0; i < inputMatrices.length; i++ ) {
				try {
					if (MapReduceTool.isHDFSFileEmpty(inputMatrices[i].getFileName())) {
						throw new DMLRuntimeException( "Can not operate on an empty file: " + inputMatrices[i].getFileName());
					}
				} catch (IOException e) {
					throw new DMLRuntimeException( "runtime error occurred -- " , e);
				}
			}
		}
	}
	
	/**
	 * Computes the replacement string for a given variable name placeholder string 
	 * (e.g., ##mVar2## or ##Var5##). The replacement is a HDFS filename for matrix 
	 * variables, and is the actual value (stored in symbol table) for scalar variables.
	 * 
	 * @param inst
	 * @param varName
	 * @param map
	 * @return
	 * @throws DMLRuntimeException
	 */
	private static String getVarNameReplacement(String inst, String varName, LocalVariableMap map) throws DMLRuntimeException {
		Data val = map.get(varName);
		if ( val != null ) {
			String replacement = null;
			if (val.getDataType() == DataType.MATRIX) {
				replacement = ((MatrixObject)val).getFileName();
			}

			if (val.getDataType() == DataType.SCALAR)
				replacement = "" + ((ScalarObject) val).getStringValue();
			return replacement;
		}
		else {
			throw new DMLRuntimeException("Variable ("+varName+") in Instruction ("+inst+") is not found in the variablemap.");
		}
	}
	
	/** 
	 * Replaces ALL placeholder strings (such as ##mVar2## and ##Var5##) in a single instruction.
	 *  
	 * @param inst
	 * @param map
	 * @return
	 * @throws DMLRuntimeException
	 */
	private static String updateInstLabels(String inst, LocalVariableMap map) throws DMLRuntimeException {
		if ( inst.contains(Lop.VARIABLE_NAME_PLACEHOLDER) ) {
			int skip = Lop.VARIABLE_NAME_PLACEHOLDER.toString().length();
			while ( inst.contains(Lop.VARIABLE_NAME_PLACEHOLDER) ) {
				int startLoc = inst.indexOf(Lop.VARIABLE_NAME_PLACEHOLDER)+skip;
				String varName = inst.substring(startLoc, inst.indexOf(Lop.VARIABLE_NAME_PLACEHOLDER, startLoc));
				String replacement = getVarNameReplacement(inst, varName, map);
				inst = inst.replaceAll(Lop.VARIABLE_NAME_PLACEHOLDER + varName + Lop.VARIABLE_NAME_PLACEHOLDER, replacement);
			}
		}
		return inst;
	}
	
	/**
	 * Takes a delimited string of instructions, and replaces ALL placeholder labels 
	 * (such as ##mVar2## and ##Var5##) in ALL instructions.
	 *  
	 * @param instList
	 * @param labelValueMapping
	 * @return
	 * @throws DMLRuntimeException
	 */
	public static String updateLabels (String instList, LocalVariableMap labelValueMapping) throws DMLRuntimeException {

		if ( !instList.contains(Lop.VARIABLE_NAME_PLACEHOLDER) )
			return instList;
		
		StringBuilder updateInstList = new StringBuilder();
		String[] ilist = instList.split(Lop.INSTRUCTION_DELIMITOR); 
		
		for ( int i=0; i < ilist.length; i++ ) {
			if ( i > 0 )
				updateInstList.append(Lop.INSTRUCTION_DELIMITOR);
			
			updateInstList.append( updateInstLabels(ilist[i], labelValueMapping));
		}
		return updateInstList.toString();
	}

	/*private static String[] updateLabels(String[] inst, LocalVariableMap labelValueMapping) throws DMLRuntimeException {
		String[] str_array = new String[inst.length];
		for (int i = 0; i < inst.length; i++) {
			str_array[i] = updateInstLabels(inst[i], labelValueMapping);
		}
		return str_array;
	}*/
	
	/**
	 * Method to determine whether to execute a particular job in local-mode or 
	 * cluster-mode. This decision is take based on the "jobType" 
	 * (GMR, Reblock, etc.) as well as the input matrix dimensions, 
	 * given by "stats". Currently, the thresholds are chosen empirically.
	 * 
	 * @param jobType
	 * @param stats
	 * @return
	 * @throws DMLRuntimeException 
	 */
	@Deprecated
	public static ExecMode getExecMode(JobType jt, MatrixCharacteristics[] stats) throws DMLRuntimeException {
		
		//if ( DMLScript.rtplatform == RUNTIME_PLATFORM.SINGLE_NODE )
		//	return ExecMode.LOCAL;
		
		if ( !flagLocalModeOpt )
			return ExecMode.CLUSTER;
		
		switch ( jt ) {
		case GMR:
		case DATAGEN:
			if ( compareInputDimensions(stats, 3) )
				return ExecMode.LOCAL;
			break;
		case REBLOCK:
			if ( compareInputDimensions(stats, 1) )
				return ExecMode.LOCAL;
			break;
		case MMCJ:
			if ( compareInputDimensions(stats, 2) )
				return ExecMode.LOCAL;
			break;
		case MMRJ:
			if ( compareInputDimensions(stats, 1) ) // this needs to be verified (empirically)
				return ExecMode.LOCAL;
			break;
		case CM_COV:
		case PARTITION:
		case SORT:
		case COMBINE:
			if ( compareInputDimensions(stats, 1) ) // this needs to be verified (empirically)
				return ExecMode.LOCAL;
			break;
		default:
			throw new DMLRuntimeException("Unknown job type (" + jt.getName() + ") while determining the execution mode.");
		}
		
		return ExecMode.CLUSTER;
	}
	
	private static boolean compareInputDimensions(MatrixCharacteristics[] stats, int numBlocks ) {
		for(int i=0; i < stats.length; i++ ) {
			// check if the input dimensions are smaller than the specified number of blocks
			if ( stats[i].numRows != -1
					&& stats[i].numRows <= numBlocks * DMLTranslator.DMLBlockSize
					&& stats[i].numColumns != -1
					&& stats[i].numColumns <= numBlocks * DMLTranslator.DMLBlockSize) {
				continue;
			}
			else 
				return false;
		}
		return true;
	}
	
	/**
	 * 
	 * @param inputMatrices
	 * @return
	 * @throws DMLRuntimeException
	 */
	private static long[] getNNZ( MatrixObject[] inputMatrices ) 
		throws DMLRuntimeException
	{
		int len = inputMatrices.length;
		long[] ret = new long[len];
		for( int i=0; i<len; i++ )
		{
			MatrixObject mo = inputMatrices[i];
			if( mo != null )
				ret[i] = mo.getNnz();
			else
				ret[i] = -1;
		}
			
		return ret;
	}
	
	/**
	 * 
	 * @param inst
	 * @param shuffleInst
	 * @param inputMatrices
	 * @param outputMatrices
	 * @return
	 * @throws DMLUnsupportedOperationException
	 * @throws DMLRuntimeException
	 */
	private static JobReturn executeInMemoryReblockOperations( MRJobInstruction inst, String shuffleInst, MatrixObject[] inputMatrices, MatrixObject[] outputMatrices ) 
		throws DMLUnsupportedOperationException, DMLRuntimeException
	{
		MatrixCharacteristics[] mc = new MatrixCharacteristics[outputMatrices.length];
		ReblockInstruction[] rblkSet = MRInstructionParser.parseReblockInstructions(shuffleInst);
		byte[] results = inst.getIv_resultIndices();
		for( ReblockInstruction rblk : rblkSet )
		{
			//CP Reblock through caching framework (no copy required: same data, next op copies) 
			MatrixBlock mb = inputMatrices[rblk.input].acquireRead();
			for( int i=0; i<results.length; i++ )
				if( rblk.output == results[i] )
				{
					outputMatrices[i].acquireModify( mb );
					outputMatrices[i].release();
					mc[i] = new MatrixCharacteristics(mb.getNumRows(),mb.getNumColumns(), rblk.brlen, rblk.bclen, mb.getNonZeros());
				}
			inputMatrices[rblk.input].release();
		}
		
		return  new JobReturn( mc, inst.getOutputInfos(), true);
	}
	
	private static JobReturn executeInMemoryDataGenOperations( MRJobInstruction inst, String randInst, MatrixObject[] outputMatrices ) 
		throws DMLUnsupportedOperationException, DMLRuntimeException
	{
		MatrixCharacteristics[] mc = new MatrixCharacteristics[outputMatrices.length];
		DataGenMRInstruction[] dgSet = MRInstructionParser.parseDataGenInstructions(randInst);
		byte[] results = inst.getIv_resultIndices();
		for( DataGenMRInstruction ldgInst : dgSet )
		{
			if( ldgInst instanceof RandInstruction )
			{
				//CP Rand block operation 
				RandInstruction lrand = (RandInstruction)ldgInst; 
				
				//MatrixBlock mb = MatrixBlock.randOperationsOLD((int)lrand.rows, (int)lrand.cols, lrand.sparsity, lrand.minValue, lrand.maxValue, lrand.probabilityDensityFunction, lrand.seed);
				MatrixBlock mb = MatrixBlock.randOperations((int)lrand.rows, (int)lrand.cols, lrand.rowsInBlock, lrand.colsInBlock, lrand.sparsity, lrand.minValue, lrand.maxValue, lrand.probabilityDensityFunction, lrand.seed);
				for( int i=0; i<results.length; i++ )
					if( lrand.output == results[i] )
					{
						outputMatrices[i].acquireModify( mb );
						outputMatrices[i].release();
						mc[i] = new MatrixCharacteristics(mb.getNumRows(),mb.getNumColumns(), lrand.rowsInBlock, lrand.colsInBlock, mb.getNonZeros());
					}
			}
			else if( ldgInst instanceof SeqInstruction )
			{
				SeqInstruction lseq = (SeqInstruction) ldgInst;
				MatrixBlock mb = MatrixBlock.seqOperations(lseq.fromValue, lseq.toValue, lseq.incrValue);
				for( int i=0; i<results.length; i++ )
					if( lseq.output == results[i] )
					{
						outputMatrices[i].acquireModify( mb );
						outputMatrices[i].release();
						mc[i] = new MatrixCharacteristics(mb.getNumRows(),mb.getNumColumns(), lseq.rowsInBlock, lseq.colsInBlock, mb.getNonZeros());
					}
			}
		}
		
		return  new JobReturn( mc, inst.getOutputInfos(), true);
	}
}
