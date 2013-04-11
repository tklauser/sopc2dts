/*
sopc2dts - Devicetree generation for Altera systems

Copyright (C) 2011 - 2013 Walter Goossens <waltergoossens@home.nl>

This library is free software; you can redistribute it and/or
modify it under the terms of the GNU Lesser General Public
License as published by the Free Software Foundation; either
version 2.1 of the License, or (at your option) any later version.

This library is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
Lesser General Public License for more details.

You should have received a copy of the GNU Lesser General Public
License along with this library; if not, write to the Free Software
Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
*/
package sopc2dts.lib.components;

import java.util.NoSuchElementException;
import java.util.Vector;
import sopc2dts.Logger;
import sopc2dts.Logger.LogLevel;
import sopc2dts.lib.AvalonSystem.SystemDataType;
import sopc2dts.lib.BasicElement;
import sopc2dts.lib.Parameter;
import sopc2dts.lib.AvalonSystem;
import sopc2dts.lib.BoardInfo;
import sopc2dts.lib.Connection;
import sopc2dts.lib.components.base.SICUnknown;
import sopc2dts.lib.devicetree.DTHelper;
import sopc2dts.lib.devicetree.DTNode;
import sopc2dts.lib.devicetree.DTProperty;
import sopc2dts.lib.devicetree.DTPropBool;
import sopc2dts.lib.devicetree.DTPropHexNumber;
import sopc2dts.lib.devicetree.DTPropNumber;
import sopc2dts.lib.devicetree.DTPropPHandle;
import sopc2dts.lib.devicetree.DTPropString;

public class BasicComponent extends BasicElement {
	private static final String EMBSW_DTS_PARAMS = "embeddedsw.dts.params.";
	private static final String EMBSW_DTS_COMPAT = "embeddedsw.dts.compatible";
	private static final String EMBSW_CMACRO = "embeddedsw.CMacro";
	
	public enum parameter_action { NONE, CMACRCO, ALL };
	private String instanceName;
	private String className;
	protected String version;
	protected Vector<Interface> vInterfaces = new Vector<Interface>();
	protected SopcComponentDescription scd;
	
	public BasicComponent(String cName, String iName, String ver,SopcComponentDescription scd)
	{
		this.className = cName;
		setScd(scd);
		this.instanceName = iName;
		this.version = ver;
	}
	protected BasicComponent(BasicComponent bc)
	{
		super(bc);
		this.instanceName = bc.instanceName;
		this.className = bc.className;
		this.version = bc.version;
		this.scd = bc.scd;
		this.vInterfaces = bc.vInterfaces;
		for(Interface intf : vInterfaces)
		{
			intf.setOwner(this);
		}
	}
	protected Vector<Long> getReg(BasicComponent master)
	{
		Vector<Long> vRegs = new Vector<Long>();		
		for(Interface intf : vInterfaces)
		{
			if(intf.isMemorySlave())
			{
				//Check all interfaces for a connection to master
				Connection conn = null;
				for(int i=0; (i<intf.getConnections().size()) && (conn==null); i++)
				{
					if(intf.getConnections().get(i).getMasterModule().equals(master))
					{
						conn = intf.getConnections().get(i);
					}
				}
				if((conn!=null) && (intf!=null))
				{
					DTHelper.addAllLongs(vRegs, getAddrFromConnection(conn));
					DTHelper.addAllLongs(vRegs, intf.getInterfaceValue());
				}
			}
		}
		return vRegs;
	}
	protected BasicComponent getInterruptParent(Interface intf, BoardInfo bi) {
		BasicComponent irqParent = null;
		for(Connection c : intf.getConnections()) {
			BasicComponent master = c.getMasterModule();
			if(bi.isValidIRQMaster(master))
			{
				if(irqParent == null) {
					irqParent = master;
				} else {
					Logger.logln(intf.getOwner().getInstanceName() + '.' + intf.getName() + 
							": Multiple interrupt parents per irq-port are not supported! We're using " + irqParent.getInstanceName() + " we're not adding " + master.getInstanceName() + " class " + master.getClassName(), LogLevel.WARNING);
				}
			}
		}
		return irqParent;
	}
	protected BasicComponent getInterrupts(Vector<Long> vIrqs, BoardInfo bi)
	{
		BasicComponent irqParent = null;
		for(Interface intf : getInterfaces())
		{
			if(intf.isIRQSlave())
			{
				BasicComponent irqp = getInterruptParent(intf, bi);
				if(irqp!=null)
				{
					if(irqParent == null) {
						irqParent = irqp;
					} else if (irqParent != irqp) {
						Logger.logln(instanceName + ": Multiple interrupt parents per component are not supported.", LogLevel.WARNING);
					}
					for(Connection c : intf.getConnections()) {
						if(c.getMasterModule().equals(irqParent)) {
							DTHelper.addAllLongs(vIrqs, c.getConnValue());
						}
					}
				}
			}
		}
		return irqParent;
	}
	public DTNode toDTNode(BoardInfo bi,Connection conn)
	{
		DTNode node = new DTNode(getScd().getGroup() + "@0x" + getAddrFromConnectionStr(conn), instanceName);
		if((getScd().getGroup().equalsIgnoreCase("cpu"))||(getScd().getGroup().equalsIgnoreCase("memory")))
		{
			node.addProperty(new DTPropString("device_type",getScd().getGroup()));
		}
		DTPropString compPropString = new DTPropString("compatible", getScd().getCompatibles(version));
		node.addProperty(compPropString);
		//Registers
		Vector<Long> vRegs = getReg((conn != null ? conn.getMasterModule() : null));
		if(vRegs.size()>0)
		{
			DTPropHexNumber p = new DTPropHexNumber("reg",vRegs);
			int width = 2;
			if(conn!=null) {
				width = conn.getSlaveInterface().getPrimaryWidth() + conn.getSlaveInterface().getSecondaryWidth();
			}
			p.setNumValuesPerRow(width);
			node.addProperty(p);
		}

		//Interrupts
		Vector<Long> vIrqs = new Vector<Long>();
		BasicComponent irqParent = getInterrupts(vIrqs,bi);
		if(irqParent!=null)
		{
			node.addProperty(new DTPropPHandle("interrupt-parent", irqParent.getInstanceName()));
			node.addProperty(new DTPropNumber("interrupts",vIrqs));
		}
		if(isInterruptMaster())
		{
			node.addProperty(new DTPropBool("interrupt-controller"));
			node.addProperty(new DTPropNumber("#interrupt-cells", 
					new Long(getInterfaces(SystemDataType.INTERRUPT, true).firstElement().getPrimaryWidth())));
		}

		Vector<Parameter> vParamTodo = new Vector<Parameter>(vParameters);
		for(SopcComponentDescription.SICAutoParam ap : getScd().getAutoParams())
		{
			Parameter bp = getParamByName(ap.getSopcInfoName());
			if(bp!=null)
			{
				node.addProperty(bp.toDTProperty(ap.getDtsName(), 
						Parameter.getDataTypeByName(ap.getForceType())));
				vParamTodo.remove(bp);
			} else if(ap.getDtsName().equalsIgnoreCase("clock-frequency"))
			{
				node.addProperty(new DTPropNumber(ap.getDtsName(), getClockRate()));
			} else if(ap.getDtsName().equalsIgnoreCase("regstep"))
			{
				node.addProperty(new DTPropNumber(ap.getDtsName(), 4L));
			} else if(ap.getFixedValue() != null)
			{
				DTProperty prop = createFixedProp(ap);
				if (prop != null)
				{
					node.addProperty(prop);
				}
            }
		}		
		if(vParamTodo.size()>0)
		{
			for(Parameter bp : vParamTodo)
			{
				String assName = bp.getName();
				if (assName.equalsIgnoreCase(EMBSW_DTS_COMPAT)) {
					String[] vals = bp.getValue().split("\\s+");
					compPropString.addStrings(vals);
					assName = null;
				} else if (assName.startsWith(EMBSW_DTS_PARAMS)) {
					assName = assName.substring(EMBSW_DTS_PARAMS.length());
					node.addProperty(bp.toDTProperty(assName));
					assName = null;
				} else if(assName.startsWith(EMBSW_CMACRO) && (bi.getDumpParameters() != parameter_action.NONE)) {
					assName = assName.substring(EMBSW_CMACRO.length());
				} else if(bi.getDumpParameters() == parameter_action.CMACRCO) {
					assName = null;
				} else {
					assName = null;
				}
				if(assName!=null)
				{
					assName = assName.replace('_', '-');
					node.addProperty(bp.toDTProperty(scd.getVendor() + ',' + assName));
				}
			}
		}
		return node;
	}
	private DTProperty createFixedProp(SopcComponentDescription.SICAutoParam ap)
	{
		DTProperty prop = null;
		String fixedValue = ap.getFixedValue();
		String dtsName = ap.getDtsName();
		String forceType = ap.getForceType();
		try {
			if (forceType.equalsIgnoreCase("unsigned"))
			{
				prop = new DTPropNumber(dtsName, Long.parseLong(fixedValue));
			} else if (forceType.equalsIgnoreCase("string"))
			{
				prop = new DTPropString(dtsName, fixedValue);
			}
		} catch (IllegalArgumentException e) 
		{
			prop = null; 
		}
		return prop;
	}
	public Interface getInterfaceByName(String ifName)
	{
		for(Interface intf : getInterfaces())
		{
			if(intf.getName().equalsIgnoreCase(ifName))
			{
				return intf;
			}
		}
		return null;
	}
	public boolean isInterruptMaster()
	{
		for(Interface intf : getInterfaces())
		{
			if(intf.isIRQMaster())
			{
				return true;
			}
		}
		return false;
	}

	public void setScd(SopcComponentDescription scd) {
		if(scd!=null)
		{
			this.scd = scd;
		} else {
			this.scd = new SICUnknown(className);
		}
	}
	public SopcComponentDescription getScd() {
		return scd;
	}
	public void setInstanceName(String instanceName) {
		this.instanceName = instanceName;
	}
	public String getInstanceName() {
		return instanceName;
	}
	public void addInterface(Interface intf) {
		intf.setOwner(this);
		vInterfaces.add(intf);
	}
	public Vector<Interface> getInterfaces() {
		return vInterfaces;
	}
	public Vector<Interface> getInterfaces(SystemDataType ofType, Boolean isMaster)
	{
		Vector<Interface> vRes = new Vector<Interface>();
		for(Interface intf: vInterfaces)
		{
			if(((ofType == null) || (intf.getType().equals(ofType))) &&
					((isMaster == null) || (intf.isMaster == isMaster)))
			{
				vRes.add(intf);
			}
		}
		return vRes;
	}
	public void removeInterface(Interface intf) {
		vInterfaces.remove(intf);
	}
	public Vector<Connection> getConnections(SystemDataType ofType, Boolean isMaster)
	{
		return getConnections(ofType, isMaster, null);
	}
	public Vector<Connection> getConnections(SystemDataType ofType, Boolean isMaster, BasicComponent toComponent)
	{
		Vector<Connection> conns = new Vector<Connection>();
		Vector<Interface> vInterf = getInterfaces(ofType, isMaster);
		for(Interface intf : vInterf)
		{
			for(Connection conn : intf.getConnections())
			{
				if((toComponent == null) || 
						(intf.isMaster && conn.getSlaveModule().equals(toComponent)) ||
						(!intf.isMaster && conn.getMasterModule().equals(toComponent)))
				{
					conns.add(conn);
				}
			}
		}
		return conns;
	}
	protected String getAddrFromConnectionStr(Connection conn)
	{
		long[] tmp = getAddrFromConnection(conn);
		String res = "";
		for(int i=0; i<tmp.length; i++) {
			if(i==0) {
				res = Long.toHexString(tmp[0]);
			} else {
				res += String.format("%08X", tmp[i]);
			}
		}
		return res;
	}
	protected long[] getAddrFromConnection(Connection conn)
	{
		return conn.getConnValue();
	}

	public long getClockRate()
	{
		long rate = 0;
		for(Interface intf : vInterfaces)
		{
			if(intf.isClockSlave())
			{
				try {
					rate = DTHelper.longArrToLong(intf.getConnections().firstElement().getConnValue());
				} catch(ArrayIndexOutOfBoundsException e) {
					Logger.logException(e);
				} catch(NoSuchElementException e) {
					Logger.logException(e);
				}
			}
		}
		return rate;
	}
	public Integer getPreferredPriWidthForIf(String iName, SystemDataType dt, boolean master) {
		return null;
	}
	public Integer getPreferredSecWidthForIf(String iName, SystemDataType dt, boolean master) {
		return null;
	}
	/** @brief Whether or not this BasicComponent has a Memory master interface.
	 * 
	 * @return true when a memory-mapped master interface exists
	 */
	public boolean hasMemoryMaster()
	{
		for(Interface intf : vInterfaces)
		{
			if(intf.isMemoryMaster()) return true;
		}
		return false;
	}
	/** @brief Removes the BasicComponent from a given AvalonSystem if possible.
	 * 
	 * Subclasses can implement this to optimize systems and/or flatten 
	 * otherwise needless complex systems.
	 * 
	 * @return True when the system is modified
	 */
	public boolean removeFromSystemIfPossible(AvalonSystem sys)
	{
		return false;
	}
	public String getClassName() {
		return className;
	}
}
