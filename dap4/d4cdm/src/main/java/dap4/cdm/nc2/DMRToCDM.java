/* Copyright 2012, UCAR/Unidata.
   See the LICENSE file for more information.
*/

package dap4.cdm.nc2;

import dap4.cdm.CDMTypeFcns;
import dap4.cdm.CDMUtil;
import dap4.cdm.NodeMap;
import dap4.core.data.DSP;
import dap4.core.dmr.*;
import dap4.core.util.Convert;
import dap4.core.util.CoreTypeFcns;
import dap4.core.util.DapException;
import dap4.core.util.DapSort;
import dap4.dap4lib.LibTypeFcns;
import ucar.ma2.DataType;
import ucar.ma2.ForbiddenConversionException;
import ucar.nc2.*;
import ucar.nc2.stream.NcStreamProto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Convert a DSP to corresponding NetcdfFile CDM metadata.
 * Note that we have a problem with <map>.
 */
public class DMRToCDM
{
    //////////////////////////////////////////////////
    // Instance Variables

    protected DapNetcdfFile ncfile;
    protected DSP dsp;
    protected DapDataset dmr;

    protected NodeMap nodemap;

    //////////////////////////////////////////////////
    // Constructors

    /**
     * @param ncfile - the NetcdfFile object
     * @param dsp    - the DSP being wrapped
     */

    public DMRToCDM(DapNetcdfFile ncfile, DSP dsp)
            throws DapException
    {
        this.ncfile = ncfile;
        this.dsp = dsp;
        this.dmr = dsp.getDMR();
        this.nodemap = new NodeMap();
    }

    //////////////////////////////////////////////////
    // API

    /**
     * Do the conversion and return a NodeMap
     * representing the conversion.
     *
     * @throws DapException
     */

    public NodeMap
    create()
            throws DapException
    {
        // Netcdf Dataset will already have a root group
        Group cdmroot = ncfile.getRootGroup();
        nodemap.put(this.dmr, cdmroot);
        fillGroup(cdmroot, this.dmr, ncfile, nodemap);
        return nodemap;
    }

    protected void
    fillGroup(Group cdmparent, DapGroup dapparent, NetcdfFile ncfile, NodeMap nodemap)
            throws DapException
    {
        // Create dimensions in this group
        for(DapDimension dim : dapparent.getDimensions()) {
            if(!dim.isShared())
                continue; // anonymous
            createDimension(dim, cdmparent, nodemap);
        }

        // Create enums in this group
        for(DapEnumeration dapenum : dapparent.getEnums()) {
            DapType basetype = dapenum.getBaseType();
            DataType cdmbasetype = CDMTypeFcns.enumTypeFor(basetype);
            Map<Integer, String> map = new HashMap<Integer, String>();
            List<String> ecnames = dapenum.getNames();
            for(String ecname : ecnames) {
                DapEnumConst value = dapenum.lookup(ecname);
                map.put(value.getIntValue(), ecname);
            }

            EnumTypedef cdmenum = new EnumTypedef(dapenum.getShortName(),
                    map, cdmbasetype);
            nodemap.put(dapenum, cdmenum);
            cdmparent.addEnumeration(cdmenum);
        }

        // Create variables
        for(DapVariable var : dapparent.getVariables()) {
            createVar(var, ncfile, nodemap, cdmparent, null);
        }

        // Create subgroups
        for(DapGroup subgroup : dapparent.getGroups()) {
            createGroup(subgroup, cdmparent, ncfile, nodemap);
        }

        // Create group level attributes
        for(Map.Entry<String, DapAttribute> entry : dapparent.getAttributes().entrySet()) {
            DapAttribute attr = entry.getValue();
            Attribute cdmattr = createAttribute(attr, nodemap);
            cdmparent.addAttribute(cdmattr);
        }
    }

    protected void
    createGroup(DapGroup dapgroup, Group cdmparent,
                NetcdfFile ncfile, NodeMap nodemap)
            throws DapException
    {
        Group cdmgroup = new Group(ncfile, cdmparent, dapgroup.getShortName());
        nodemap.put(dapgroup, cdmgroup);
        fillGroup(cdmgroup, dapgroup, ncfile, nodemap);
        if(cdmgroup != null)
            cdmparent.addGroup(cdmgroup);
    }

    /**
     * Create a variable or field
     *
     * @param dapvar          the template variable
     * @param ncfile          the containing NetcdfFile (really NetcdfDataset)
     * @param nodemap         for tracking cdm nodes <-> dap nodes
     * @param cdmgroup        the containing CDM group
     * @param cdmparentstruct the containing CDM structure (or null)
     */
    protected void
    createVar(DapVariable dapvar, NetcdfFile ncfile, NodeMap nodemap,
              Group cdmgroup, Structure cdmparentstruct)
            throws DapException
    {
        Variable cdmvar = null;
        if(dapvar.getSort() == DapSort.ATOMICVARIABLE) {
            DapAtomicVariable atomvar = (DapAtomicVariable) dapvar;
            cdmvar = new Variable(ncfile,
                    cdmgroup,
                    cdmparentstruct,
                    atomvar.getShortName());
            DapType basetype = atomvar.getBaseType();
            DataType cdmbasetype;
            if(basetype.isEnumType())
                cdmbasetype = CDMTypeFcns.enumTypeFor(basetype);
            else
                cdmbasetype = CDMTypeFcns.daptype2cdmtype(basetype);
            if(cdmbasetype == null)
                throw new DapException("Unknown basetype:" + basetype);
            cdmvar.setDataType(cdmbasetype);
            if(basetype.isEnumType()) {
                EnumTypedef cdmenum = (EnumTypedef) nodemap.get(basetype);
                if(cdmenum == null)
                    throw new DapException("Unknown enumeration type:" + basetype.toString());
                cdmvar.setEnumTypedef(cdmenum);
            }
            nodemap.put(dapvar, cdmvar);
        } else if(dapvar.getSort() == DapSort.STRUCTURE) {
            DapStructure dapstruct = (DapStructure) dapvar;
            Structure cdmstruct = new Structure(ncfile,
                    cdmgroup,
                    cdmparentstruct,
                    dapstruct.getShortName());
            cdmvar = cdmstruct;
            nodemap.put(dapvar, cdmvar);
            // Add the fields
            for(DapVariable field : dapstruct.getFields()) {
                createVar(field, ncfile, nodemap, cdmgroup, cdmstruct);
            }
        } else if(dapvar.getSort() == DapSort.SEQUENCE) {
            DapSequence dapseq = (DapSequence) dapvar;
            // In general one would convert the sequence
            // to a CDM sequence with vlen
            // so Sequence {...} s[d1]...[dn]
            // => Sequence {...} s[d1]...[dn]
            Sequence cdmseq = new Sequence(ncfile,
                    cdmgroup,
                    cdmparentstruct,
                    dapseq.getShortName());
            cdmvar = cdmseq;
            nodemap.put(dapvar, cdmvar);
            // Add the fields
            for(DapVariable field : dapseq.getFields()) {
                createVar(field, ncfile, nodemap, cdmgroup, cdmseq);
            }
            // If the rank > 0, then add warning attribute
            if(dapvar.getRank() > 0) {
                List value = new ArrayList();
                value.add("CDM does not support Sequences with rank > 0");
                Attribute warning = new Attribute("_WARNING:", value);
                cdmvar.addAttribute(warning);
            }

        } else
            assert (false) : "Unknown variable sort: " + dapvar.getSort();
        int rank = dapvar.getRank();
        List<Dimension> cdmdims = new ArrayList<Dimension>(rank + 1); // +1 for vlen
        for(int i = 0; i < rank; i++) {
            DapDimension dim = dapvar.getDimension(i);
            Dimension cdmdim = createDimensionRef(dim, cdmgroup, nodemap);
            cdmdims.add(cdmdim);
        }
        if(dapvar.getSort() == DapSort.SEQUENCE) {
            // Add the final vlen
            cdmdims.add(Dimension.VLEN);
        }
        cdmvar.setDimensions(cdmdims);
        // Create variable's attributes
        for(String key : dapvar.getAttributes().keySet()) {
            DapAttribute attr = dapvar.getAttributes().get(key);
            Attribute cdmattr = createAttribute(attr, nodemap);
            cdmvar.addAttribute(cdmattr);
        }
        if(cdmparentstruct != null)
            cdmparentstruct.addMemberVariable(cdmvar);
        else if(cdmgroup != null)
            cdmgroup.addVariable(cdmvar);

    }

    protected void
    createDimension(DapDimension dapdim, Group cdmgroup, NodeMap nodemap)
            throws DapException
    {
        if(dapdim.isVariableLength()) {
            nodemap.put(dapdim, Dimension.VLEN);
        } else if(dapdim.isShared()) {
            if(nodemap.containsKey(dapdim))
                throw new DapException("Attempt to declare dimension twice:" + dapdim.getFQN());
            Dimension cdmdim = new Dimension(dapdim.getShortName(), (int) dapdim.getSize(), true, false, dapdim.isVariableLength());
            nodemap.put(dapdim, cdmdim);
            cdmgroup.addDimension(cdmdim);
        } else
            throw new DapException("Attempt to declare an anonymous dimension");
    }

    protected Dimension
    createDimensionRef(DapDimension dim, Group cdmgroup, NodeMap nodemap)
            throws DapException
    {
        Dimension cdmdim = null;
        if(dim.isShared())
            cdmdim = (Dimension) nodemap.get(dim);
        else // anonymous
            cdmdim = new Dimension(null, (int) dim.getSize(), false, false, false);
        if(cdmdim == null)
            throw new DapException("Unknown dimension: " + dim.getFQN());
        return cdmdim;
    }

    protected EnumTypedef
    createEnum(DapEnumeration dapenum, Group cdmparent,
               NodeMap nodemap)
            throws DapException
    {
        DapType basetype = dapenum.getBaseType();
        DataType cdmbasetype = CDMTypeFcns.enumTypeFor(basetype);
        Map<Integer, String> map = new HashMap<Integer, String>();
        List<String> ecnames = dapenum.getNames();
        for(String ecname : ecnames) {
            DapEnumConst value = dapenum.lookup(ecname);
            map.put(value.getIntValue(), ecname);
        }

        EnumTypedef cdmenum = new EnumTypedef(dapenum.getShortName(),
                map, cdmbasetype);
        nodemap.put(dapenum, cdmenum);
        cdmparent.addEnumeration(cdmenum);
        return cdmenum;
    }

    /**
     * Our goal is to convert, where possible, to a
     * list of objects to a list of values
     * acceptable to the ucar.nc2.Attribute class
     *
     * @param dapattr The dap attribute whose values need to be converted
     * @param nodemap Insert the created ucar.nc2.Attribute into this map
     * @return The created ucar.nc2.Attribute
     */
    protected Attribute
    createAttribute(DapAttribute dapattr, NodeMap nodemap)
    {
        return createAttribute(null, dapattr, nodemap);
    }

    protected Attribute
    createAttribute(String prefix, DapAttribute dapattr, NodeMap nodemap)
    {
        Attribute cdmattr = null;
        switch (dapattr.getSort()) {
        case ATTRIBUTE:
            DapType basetype = dapattr.getBaseType();
            if(!(basetype.isLegalAttrType()))
                throw new ForbiddenConversionException("Illegal attribute type:" + basetype.toString());
            DataType cdmtype = CDMTypeFcns.daptype2cdmtype(basetype);
            EnumTypedef en = (EnumTypedef)(basetype.isEnumType() ? nodemap.get(basetype) : null);
            Object[] dapvalues = dapattr.getValues();
            List cdmvalues = new ArrayList();
            for(int i = 0; i < dapvalues.length; i++) {
                Object o = dapvalues[i];
                o = CoreTypeFcns.attributeConvert(basetype,o);
                if(o == null)
                    throw new ForbiddenConversionException("Illegal attribute type:" + basetype.toString());
                Object ocdm = CDMTypeFcns.attributeConvert(cdmtype,en,o);
                cdmvalues.add(ocdm);
            }
            cdmattr = new Attribute(dapattr.getShortName(), cdmvalues);
            break;
        case ATTRIBUTESET:
            String setname = dapattr.getShortName();
            prefix = (prefix == null ? setname : prefix + "_" + setname);
            for(String key : dapattr.getAttributes().keySet()) {
                DapAttribute subattr = dapattr.getAttributes().get(key);
                cdmattr = createAttribute(prefix, subattr, nodemap);
            }
            break;
        case OTHERXML:
            cdmvalues = new ArrayList();
            cdmvalues.add("OtherXML");
            cdmattr = new Attribute("OtherXML", cdmvalues);
            break;
        }
        nodemap.put(dapattr, cdmattr);
        return cdmattr;
    }

}
