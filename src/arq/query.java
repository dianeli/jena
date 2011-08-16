/*
 * (c) Copyright 2006, 2007, 2008, 2009 Hewlett-Packard Development Company, LP
 * All rights reserved.
 * [See end of file]
 */

package arq;

import org.openjena.atlas.io.IndentedWriter ;
import arq.cmd.CmdException ;
import arq.cmd.TerminationException ;
import arq.cmdline.ArgDecl ;
import arq.cmdline.CmdARQ ;
import arq.cmdline.ModDataset ;
import arq.cmdline.ModDatasetGeneralAssembler ;
import arq.cmdline.ModEngine ;
import arq.cmdline.ModQueryIn ;
import arq.cmdline.ModResultsOut ;
import arq.cmdline.ModTime ;

import com.hp.hpl.jena.query.ARQ ;
import com.hp.hpl.jena.query.Dataset ;
import com.hp.hpl.jena.query.Query ;
import com.hp.hpl.jena.query.QueryException ;
import com.hp.hpl.jena.query.QueryExecution ;
import com.hp.hpl.jena.query.QueryExecutionFactory ;
import com.hp.hpl.jena.shared.JenaException ;
import com.hp.hpl.jena.sparql.ARQInternalErrorException ;
import com.hp.hpl.jena.sparql.mgt.Explain ;
import com.hp.hpl.jena.sparql.resultset.ResultSetException ;
import com.hp.hpl.jena.sparql.resultset.ResultFormat ;
import com.hp.hpl.jena.sparql.util.QueryExecUtils ;
import com.hp.hpl.jena.sparql.util.Utils ;

public class query extends CmdARQ
{
    private ArgDecl argRepeat   = new ArgDecl(ArgDecl.HasValue, "repeat") ;
    private ArgDecl argExplain  = new ArgDecl(ArgDecl.NoValue, "explain") ;
    private ArgDecl argOptimize = new ArgDecl(ArgDecl.HasValue, "opt", "optimize") ;

    protected int repeatCount = 1 ; 
    protected int warmupCount = 0 ;
    protected boolean queryOptimization = true ;
    
    protected ModTime       modTime =     new ModTime() ;
    protected ModQueryIn    modQuery =    new ModQueryIn() ;
    protected ModDataset    modDataset =  null ;
    protected ModResultsOut modResults =  new ModResultsOut() ;
    protected ModEngine     modEngine =   new ModEngine() ;
    
    public static void main (String... argv)
    {
        new query(argv).mainRun() ;
    }
    
    public query(String[] argv)
    {
        super(argv) ;
        modDataset = setModDataset() ;
        super.addModule(modQuery) ;
        super.addModule(modResults) ;
        super.addModule(modDataset) ;
        super.addModule(modEngine) ;
        super.addModule(modTime) ;

        super.getUsage().startCategory("Control") ;
        super.add(argExplain, "--explain", "Explain and log query execution") ;
        super.add(argRepeat, "--repeat=N or N,M", "Do N times or N warmup and then M times (use for timing to overcome start up costs of Java)");
        super.add(argOptimize, "--optimize=", "Turn the query optimizer on or off (default: on") ;
    }

    @Override
    protected void processModulesAndArgs()
    {
        super.processModulesAndArgs() ;
        if ( contains(argRepeat) )
        {
            String[] x = getValue(argRepeat).split(",") ;
            if ( x.length == 1 )
            {
                try { repeatCount = Integer.parseInt(x[0]) ; }
                catch (NumberFormatException ex)
                { throw new CmdException("Can't parse "+x[0]+" in arg "+getValue(argRepeat)+" as an integer") ; }
                
            }
            else if ( x.length == 2 )
            {
                try { warmupCount = Integer.parseInt(x[0]) ; }
                catch (NumberFormatException ex)
                { throw new CmdException("Can't parse "+x[0]+" in arg "+getValue(argRepeat)+" as an integer") ; }
                try { repeatCount = Integer.parseInt(x[1]) ; }
                catch (NumberFormatException ex)
                { throw new CmdException("Can't parse "+x[1]+" in arg "+getValue(argRepeat)+" as an integer") ; }
            }
            else
                throw new CmdException("Wrong format for repeat count: "+getValue(argRepeat)) ;
        }
        if ( isVerbose() )
            ARQ.getContext().setTrue(ARQ.symLogExec) ;
        
        if ( hasArg(argExplain) )
            ARQ.setExecutionLogging(Explain.InfoLevel.ALL) ;
        
        if ( hasArg(argOptimize) )
        {
            String x1 = getValue(argOptimize).toLowerCase() ;
            if ( hasValueOfTrue(argOptimize) || x1.equals("on") || x1.equals("yes") )      queryOptimization = true ;
            else if ( hasValueOfFalse(argOptimize) || x1.equals("off") || x1.equals("no") ) queryOptimization = false ;
            else throw new CmdException("Optimization flag must be true/false/on/off/yes/no. Found: "+getValue(argOptimize)) ;
        }
    }
    
    protected ModDataset setModDataset()
    {
        return new ModDatasetGeneralAssembler() ;
    }
    
    @Override
    protected void exec()
    {
        if ( ! queryOptimization )
            ARQ.getContext().setFalse(ARQ.optimization) ;
        if ( cmdStrictMode )
            ARQ.getContext().setFalse(ARQ.optimization) ;
        
        // Warm up.
        for ( int i = 0 ; i < warmupCount ; i++ )
        {
            queryExec(false, ResultFormat.FMT_NONE) ;
        }
        
        for ( int i = 0 ; i < repeatCount ; i++ )
            queryExec(modTime.timingEnabled(),  modResults.getResultsFormat()) ;
        
        if ( modTime.timingEnabled() && repeatCount > 1 )
        {
            long avg = totalTime/repeatCount ;
            String avgStr = modTime.timeStr(avg) ;
            System.out.println("Total time: "+modTime.timeStr(totalTime)+" sec for repeat count of "+repeatCount+ " : average: "+avgStr) ;
        }
    }

    @Override
    protected String getCommandName() { return Utils.className(this) ; }
    
    @Override
    protected String getSummary() { return getCommandName()+" --data=<file> --query=<query>" ; }
    
    protected Dataset getDataset()  { return modDataset.getDataset() ; }
    
    protected long totalTime = 0 ;
    protected void queryExec(boolean timed, ResultFormat fmt)
    {
        try{
            Query query = modQuery.getQuery() ;
            if ( isVerbose() )
            {
                IndentedWriter out = new IndentedWriter(System.out, true) ;
                query.serialize(out) ;
                out.flush() ;
                System.out.println();
            }
            
            Dataset dataset = getDataset() ;
            modTime.startTimer() ;
            QueryExecution qe = QueryExecutionFactory.create(query, dataset) ;
            // Check there is a dataset
            
            if ( dataset == null && ! query.hasDatasetDescription() )
            {
                System.err.println("Dataset not specified in query nor provided on command line.");
                throw new TerminationException(1) ;
            }
            QueryExecUtils.executeQuery(query, qe,fmt) ;
            long time = modTime.endTimer() ;
            if ( timed )
            {
                totalTime += time ;
                System.out.println("Time: "+modTime.timeStr(time)+" sec") ;
            }
            qe.close() ;
        }
        catch (ARQInternalErrorException intEx)
        {
            System.err.println(intEx.getMessage()) ;
            if ( intEx.getCause() != null )
            {
                System.err.println("Cause:") ;
                intEx.getCause().printStackTrace(System.err) ;
                System.err.println() ;
            }
            intEx.printStackTrace(System.err) ;
        }
        catch (ResultSetException ex)
        {
            System.err.println(ex.getMessage()) ;
            ex.printStackTrace(System.err) ;
        }
        catch (QueryException qEx)
        {
            //System.err.println(qEx.getMessage()) ;
            throw new CmdException("Query Exeception", qEx) ;
        }
        catch (JenaException ex) { throw ex ; } 
        catch (CmdException ex) { throw ex ; } 
        catch (Exception ex)
        {
            throw new CmdException("Exception", ex) ;
        }
    }    
}

/*
 * (c) Copyright 2006, 2007, 2008, 2009 Hewlett-Packard Development Company, LP
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */