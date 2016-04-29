// vim: sw=4 expandtab cindent ts=4
import groovy.text.SimpleTemplateEngine;

import org.codehaus.groovy.runtime.StackTraceUtils;
import org.omg.CORBA.SystemException

import graxxia.Matrix;;


class SummarizeCNVs {
    
    /**
     * CNV callers to summarize results from
     */
    Map<String,RangedData> results = [:]
    
    /**
     * Targeted regions by assay, with id in column 4 for annotation (typically gene)
     */
    Regions targetRegions
    
    /**
     * Required for annotation of spanning population CNVs
     */
    String dgvFile = null
    
    /**
     * VCFs containing variants for samples for CNV calls (optional)
     */
    List<VCF> vcfs = []
    
    /**
     * Thresholds for filtering CNVs by quality when writing out CNVs
     */
    Map<String, Float> qualityFilters = [:]
    
    /**
     * Locations of variants overlapping CNV calls 
     */
    Map<String, Regions> variants = [:]
    
    List<String> samples = []
    
    TargetedCNVAnnotator cnvAnnotator = null
    
    def log = System.err
    
    static void main(String [] args) {
        
        Cli cli = new Cli(usage: "SummarizeCNVs <options>")
        
        cli.with {
            ed 'ExomeDepth results', args:1
            xhmm 'XHMM results', args:1
            cnmops 'CN Mops results', args:1
            cfr 'Conifer results', args:1
            angel 'Angel results', args:1
            ex 'Excavator results', args:1
            truth 'Postiive control CNVs', args:1
            vcf 'VCF file containing variants for a sample in results', args:Cli.UNLIMITED
            target 'Target regions with id for each region to annotate', args:1, required:true
            chr 'Process CNVs only for given chromosome', args:1
            report 'Template to use for creating HTML report', args: 1
            dgv 'Path to file containing CNVs from database of genomic variants (DGV)', args: 1
            samples 'Samples to export', args:1
            quality 'Filtering by quality score, in form caller:quality...', args:Cli.UNLIMITED
            bam 'BAM file for a sample (if provided, used to customize IGV access)', args:Cli.UNLIMITED
            bampath 'Path to BAM files to enable access using IGV (can be URL)', args:1
            name 'Name for report (displayed in header)', args:1
            tsv 'Write consolidated CNV report in tab separated format to <file>', args:1
            imgpath 'Additional path to add for images', args:1
            o 'Output file name', args:1
        }
        
        def opts = cli.parse(args)
        if(!opts)
            System.exit(1)
        
        def results = [:]
        if(opts.ed)
            results.ed = new ExomeDepthResults(opts.ed).load()
            
        if(opts.xhmm)
            results.xhmm = new XHMMResults(opts.xhmm).load()
            
        if(opts.cnmops)
            results.cnmops = new CNMopsResults(opts.cnmops).load()
            
        if(opts.angel)
            results.angel = new AngelResults(opts.angel).load()
            
        if(opts.cfr)
            results.cfr = new ConiferResults(opts.cfr).load()
            
         if(opts.truth) 
            results.truth = new PositiveControlResults(opts.truth).load() 
            
        List exportSamples = null
        if(opts.samples)
            exportSamples= opts.samples.split(",")*.trim() as List    

        String outputFileName = opts.report ? new File(opts.report).name : 'cnv_report.html'
        if(opts.o) 
            outputFileName = opts.o
            
        String reportName
        if(opts.name)
            reportName = opts.name
            
        Map qualityFilters = [:]
        if(opts.qualitys) {
            qualityFilters = opts.qualitys.collectEntries { qs ->
                def parts = qs.split(":")
                if(parts.size()!=2)
                throw new IllegalArgumentException("Quality filtering string should be in form <caller>:<quality threshold>")
                [ parts[0], parts[1].toFloat() ]
            }
        }
            
        List<VCF> vcfList = opts.vcfs ? opts.vcfs.collect { VCF.parse(it) } : []
        
        Regions target = new BED(opts.target, withExtra:true).load()
        try {
            SummarizeCNVs summarizer = new SummarizeCNVs(results:results, targetRegions:target, vcfs:vcfList, qualityFilters: qualityFilters)
            if(opts.dgv) {
                summarizer.cnvAnnotator = new TargetedCNVAnnotator(target, opts.dgv)
            }
            
            Regions cnvs = summarizer.run(exportSamples)
            if(opts.tsv) {
                summarizer.writeTSV(cnvs, opts.tsv)
            }
            
            summarizer.log.println "Writing output to " + outputFileName
            summarizer.writeReport(
                cnvs, 
                reportName, 
                outputFileName, 
                opts.report ?: "cnv_report.html", 
                false, 
                opts.bams ?: [], 
                opts.bampath,
                opts.imgpath ?: "")
        }
        catch(Exception e) {
            StackTraceUtils.sanitize(e)
            e.printStackTrace()
            System.exit(1)
        }
    }
    
    Regions run(List exportSamples) {
        
        if(this.dgvFile)
            this.cnvAnnotator = new TargetedCNVAnnotator(targetRegions, dgvFile)
        
        extractSamples()
        
        // Try to find a VCF file for each sample
        variants = samples.collectEntries { String sample ->
            [sample, vcfs.find { sample in it.samples }?.toRegions()]
        }

        if(exportSamples == null)
            exportSamples = this.samples

        Regions results = new Regions()
        for(s in exportSamples) {
            Regions sampleCnvs = extractSampleCnvs(s)
            for(cnv in sampleCnvs) {
                log.println "Merging CNV $cnv for sample '$s' type = $cnv.type"

                results.addRegion(cnv)
            }
        }
        return results
    }
    
    void writeTSV(Regions cnvs, String fileName) {
        
        println "Writing TSV report to $fileName"
        
        List<String> cnvCallers = results.keySet() as List
        
        new File(fileName).withWriter { w ->
            
            w.println((["chr","start","end","sample","type","count","stotal"] + 
                       cnvCallers + 
                       cnvCallers.collect { it+"_qual" }).join("\t"))
            
            for(Region cnv in cnvs) {
                List line = [
                    cnv.chr, 
                    cnv.from,
                    cnv.to, 
                    cnv.sample,
                    cnv.type, 
                    cnv.count, 
                    cnv.stotal 
                ] + cnvCallers.collect { caller ->
                    cnv[caller] ? "TRUE" : "FALSE"
                }  + cnvCallers.collect { caller ->
                    cnv[caller] ? cnv[caller].quality : 0
                } 
                
                w.println line.join("\t")
            }
        }
    }
    
    void writeReport(Regions cnvs, String name, String fileName, String reportTemplate="cnv_report.html", boolean inlineJs=true, List bamFiles = [], def bamFilePath=false, String imgpath="") {
        
        println "Using report template: " + reportTemplate
        
        SimpleTemplateEngine templateEngine = new SimpleTemplateEngine()
        String jsFileName = new File(reportTemplate).name.replaceAll('\\.html$','\\.js')
        String js = "<script src='cnv.js'></script>"
        InputStream templateStream 
        String jsCode = null
        if(new File(reportTemplate).exists()) {
            templateStream = new File(reportTemplate).newInputStream()
            jsCode = new File(new File(reportTemplate).parentFile, jsFileName).text
            if(inlineJs) {
                js = '<script type="text/javascript">' + 
                    jsCode +
                    '</script>'
            }
        }
        else {
            templateStream = getClass().classLoader.getResourceAsStream(reportTemplate)
            
            if(templateStream == null) {
                throw new RuntimeException("ERROR: Unable to load template " + reportTemplate)
            }
            
            jsCode = getClass().classLoader.getResourceAsStream(jsFileName).text
            if(inlineJs)  {
                js = """<script type="text/javascript">
                 ${jsCode}
                  </script>
                """
            }
        }
        
        if(!inlineJs) {
            File jsFile = new File(new File(fileName).parentFile, 'cnv.js')
            println "Writing cnv.js to " + jsFile.absolutePath
            jsFile.text = jsCode
        }
            
        templateStream.withReader { r ->
            new File(fileName).withWriter { w ->
                templateEngine.createTemplate(r).make(
                    imgpath: imgpath,
                    cnvs : cnvs,
                    batch_name : name,
                    cnv_callers : results.keySet() as List,
                    types : ['DUP','DEL'],
                    reportSamples : false,
                    cnvAnnotator : cnvAnnotator,
                    js : js,
                    bam_files : bamFiles,
                    bam_file_path : bamFilePath
                ).writeTo(w)
            }
        }
    }
    
    void extractSamples() {
        this.samples = results.collect { e ->
            e.value*.sample
        }.flatten().unique()
        
        log.println "Extracted samples from calls: $samples"
    }
    
    Regions extractSampleCnvs(String sample) {
        
        // First accumulate CNVs from all the callers into one merged object
        Regions merged = new Regions()
        results.each { caller, calls ->
            for(Region cnv in calls.grep { it.sample == sample }) {
                merged.addRegion(cnv)
            }
        }
        
        Regions flattened = merged.reduce()
        
        log.println "Sample $sample has ${flattened.numberOfRanges} CNVs"
        
        List callers = results.keySet() as List

        Regions result = new Regions()

        // Now annotate each flattened CNV with the callers that found it, and the 
        // confidence of the highest confidence call from each caller
        for(Region cnv in flattened) {
            
            annotateCNV(sample, callers, cnv)
            
            // At least one of the CNV callers must pass quality filtering
            Map qualityFiltering = callers.collectEntries { caller ->
                [caller, checkCallQuality(cnv,caller)]    
            }
            
            if(!qualityFiltering.any { it.value == false }) {
                log.println "CNV $cnv filtered out by low quality score in $qualityFiltering"
                continue
            }
            
            result.addRegion(cnv)
            log.println "Annotated CNV $cnv (${cnv.hashCode()}) for $sample of type $cnv.type"
        }
        
        for(Region cnv in result) {
            println "Set scount to " + result.numberOfRanges
            cnv.stotal = result.numberOfRanges
        }
        
        return result
    }
    
    /**
     * Return true if the caller failed quality check for the CNV
     * 
     * @param cnv
     * @param caller
     */
    def checkCallQuality(Region cnv, caller) {
        if(cnv[caller] == null) // Caller did not call this CNV
            return null

        if(qualityFilters[caller] == null)  // no threshold set for this caller
            return false

        if(cnv[caller].quality > qualityFilters[caller])
            return false

        return true // failed quality, below threshold
    }
    
    /**
     * Add contextual information to the CNV including:
     * <li>Type (del,dup)
     * <li>callers that found it
     * <li>number of callers that found it
     * <li>genes that the CNV overlaps
     * <li>variants that overlap the CNV / genes the CNV overlaps
     * @param sample
     * @param callers
     * @param cnv
     */
    void annotateCNV(String sample, List callers, Region cnv) {
        
        List foundInCallers = []
        for(String caller in callers) {
            // log.println "Find best CNV call for $caller"
            Region best = results[caller].grep { it.sample == sample && it.overlaps(cnv) }.max { it.quality?.toFloat() }
            if(best != null) {
                log.println "Best CNV for $caller is " + best + " with quality " + best?.quality
                foundInCallers << caller
            }
            cnv[caller] = best
        }
            
        def types = foundInCallers.collect { cnv[it]?.type }.grep { it }.unique()
        if(types.size()>1)
            log.println "WARNING: CNV $cnv has conflicting calls: " + types
            
            
        cnv.type = types.join(",")
        
        cnv.sample = sample
            
        cnv.count = callers.grep { it != "truth" }.count { cnv[it] != null } 
            
        cnv.genes = targetRegions.getOverlaps(cnv)*.extra.unique().join(",")
        
        // Annotate the variants if we have a VCF for this sample
        if(variants[sample]) {
            int sampleIndex = variants[sample][0].extra.header.samples.indexOf(sample)
            cnv.variants = variants[sample].getOverlaps(cnv)*.extra.grep { it.sampleDosage(sample) > 0 }
            cnv.hom = cnv.variants.count { it.dosages[sampleIndex] == 2 }
            cnv.het = cnv.variants.count { it.dosages[sampleIndex] == 1 }
            cnv.bal = Stats.mean(cnv.variants.grep { it.dosages[sampleIndex] == 1 }.collect { 
                def refDepth = it.getAlleleDepths(0)[sampleIndex]
                if(refDepth == 0)
                    return Float.NaN
                    
                it.getAlleleDepths(1)[sampleIndex] / (float)refDepth
            })
        }
            
        log.println "$cnv.count callers found $cnv of type $cnv.type in sample $sample covering genes $cnv.genes with ${cnv.variants?.size()} variants"
    }
}