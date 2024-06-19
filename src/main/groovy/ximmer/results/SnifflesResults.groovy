package ximmer.results

import gngs.*

class SnifflesResults extends CNVResults {
    
    String sourceFile
    String sample
    
    public SnifflesResults(String sourceFile, String sample=null) {
		super(sourceFile)
        this.sourceFile = sourceFile
		this.sample = sample ? sample : getSampleFromFile(sourceFile)
		
		VCF.parse(this.sourceFile) { v ->
            if(!(v.info.SVTYPE in ['DEL','DUP'])) {
                return false
            }
            
            if(Region.isMinorContig(v.chr))
                return false
                
			Region r = new Region(v.chr, v.pos, v.info.END.toInteger())
			r.type = v.info.SVTYPE
			r.sample = this.sample
			r.start = v.pos
			r.end = v.info.END.toInteger()
			r.quality = v.qual
            
			addRegion(r)
            
            return false
		}
    }
	
}

