/*  This file is part of Openrouteservice.
 *
 *  Openrouteservice is free software; you can redistribute it and/or modify it under the terms of the 
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version 2.1 
 *  of the License, or (at your option) any later version.

 *  This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. 
 *  See the GNU Lesser General Public License for more details.

 *  You should have received a copy of the GNU Lesser General Public License along with this library; 
 *  if not, see <https://www.gnu.org/licenses/>.  
 */
package heigit.ors.routing.graphhopper.extensions.flagencoders;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.profiles.DecimalEncodedValue;
import com.graphhopper.routing.profiles.EncodedValue;
import com.graphhopper.routing.profiles.FactorizedDecimalEncodedValue;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.util.PriorityCode;
import com.graphhopper.routing.weighting.PriorityWeighting;
import com.graphhopper.storage.IntsRef;
import com.graphhopper.util.Helper;
import com.graphhopper.util.PMap;

import java.util.*;

public class HeavyVehicleFlagEncoder extends VehicleFlagEncoder
{
    protected final HashSet<String> forwardKeys = new HashSet<String>(5);
    protected final HashSet<String> backwardKeys = new HashSet<String>(5);
    protected final List<String> hgvAccess = new ArrayList<String>(5);

    // Take into account acceleration calculations when determining travel speed
    protected boolean useAcceleration = false;
    
    protected int maxTrackGradeLevel = 3;

    // Encoder for storing whether the edge is on a preferred way
	private DecimalEncodedValue priorityWayEncoder;
	
    /**
     * Should be only instantied via EncodingManager
     */
    public HeavyVehicleFlagEncoder()
    {
        this(5, 5, 0);
    }

    public HeavyVehicleFlagEncoder(PMap properties)
    {
        this(properties.getInt("speed_bits", 5),
        		properties.getDouble("speed_factor", 5),
        		properties.getBool("turn_costs", false) ? 3 : 0);
        
        setBlockFords(false);
        
        maxTrackGradeLevel = properties.getInt("maximum_grade_level", 1);

        this.useAcceleration = properties.getBool("use_acceleration", false);
    }

    public HeavyVehicleFlagEncoder( int speedBits, double speedFactor, int maxTurnCosts )
    {
        super(speedBits, speedFactor, maxTurnCosts);
        restrictions.addAll(Arrays.asList("motorcar", "motor_vehicle", "vehicle", "access"));
        restrictedValues.add("private");
        restrictedValues.add("no");
        restrictedValues.add("restricted");
        restrictedValues.add("military");

        intendedValues.add("yes");
        intendedValues.add("permissive");
        intendedValues.add("designated");
        intendedValues.add("destination");  // This is needed to allow the passing of barriers that are marked as destination

        intendedValues.add("agricultural");
        intendedValues.add("forestry");
        intendedValues.add("delivery");
        intendedValues.add("bus");
        intendedValues.add("hgv");
        intendedValues.add("goods");

        hgvAccess.addAll(Arrays.asList("hgv", "goods", "bus", "agricultural", "forestry", "delivery"));

        potentialBarriers.add("gate");
        potentialBarriers.add("lift_gate");
        potentialBarriers.add("kissing_gate");
        potentialBarriers.add("swing_gate");

        absoluteBarriers.add("bollard");
        absoluteBarriers.add("stile");
        absoluteBarriers.add("turnstile");
        absoluteBarriers.add("cycle_barrier");
        absoluteBarriers.add("motorcycle_barrier");
        absoluteBarriers.add("block");

    	
        Map<String, Integer> trackTypeSpeedMap = new HashMap<String, Integer>();
        trackTypeSpeedMap.put("grade1", 20); // paved
        trackTypeSpeedMap.put("grade2", 15); // now unpaved - gravel mixed with ...
        trackTypeSpeedMap.put("grade3", 10); // ... hard and soft materials
        trackTypeSpeedMap.put("grade4", 5); // ... some hard or compressed materials
        trackTypeSpeedMap.put("grade5", 5); // ... no hard materials. soil/sand/grass

        Map<String, Integer> badSurfaceSpeedMap =  new HashMap<String, Integer>();

        badSurfaceSpeedMap.put("asphalt", -1); 
        badSurfaceSpeedMap.put("concrete", -1);
        badSurfaceSpeedMap.put("concrete:plates", -1);
        badSurfaceSpeedMap.put("concrete:lanes", -1);
        badSurfaceSpeedMap.put("paved", -1);
        badSurfaceSpeedMap.put("cement", 80);
        badSurfaceSpeedMap.put("compacted", 80);
        badSurfaceSpeedMap.put("fine_gravel", 60);
        badSurfaceSpeedMap.put("paving_stones", 40);
        badSurfaceSpeedMap.put("metal", 40);
        badSurfaceSpeedMap.put("bricks", 40);
        badSurfaceSpeedMap.put("grass", 30);
        badSurfaceSpeedMap.put("wood", 30);
        badSurfaceSpeedMap.put("sett", 30);
        badSurfaceSpeedMap.put("grass_paver", 30);
        badSurfaceSpeedMap.put("gravel", 30);
        badSurfaceSpeedMap.put("unpaved", 30);
        badSurfaceSpeedMap.put("ground", 30);
        badSurfaceSpeedMap.put("dirt", 30);
        badSurfaceSpeedMap.put("pebblestone", 30);
        badSurfaceSpeedMap.put("tartan", 30);
        badSurfaceSpeedMap.put("cobblestone", 20);
        badSurfaceSpeedMap.put("clay", 20);
        badSurfaceSpeedMap.put("earth", 15);
        badSurfaceSpeedMap.put("stone", 15);
        badSurfaceSpeedMap.put("rocky", 15);
        badSurfaceSpeedMap.put("sand", 15);
        badSurfaceSpeedMap.put("mud", 10);
        badSurfaceSpeedMap.put("unknown", 30);

        Map<String, Integer> defaultSpeedMap = new HashMap<String, Integer>();
        // autobahn
        defaultSpeedMap.put("motorway", 80);
        defaultSpeedMap.put("motorway_link", 50);
        defaultSpeedMap.put("motorroad", 80);
        // bundesstraße
        defaultSpeedMap.put("trunk", 80);
        defaultSpeedMap.put("trunk_link", 50);
        // linking bigger town
        defaultSpeedMap.put("primary", 60);  
        defaultSpeedMap.put("primary_link", 50);
        // linking towns + villages
        defaultSpeedMap.put("secondary", 60);
        defaultSpeedMap.put("secondary_link", 50);
        // streets without middle line separation
        defaultSpeedMap.put("tertiary", 60);
        defaultSpeedMap.put("tertiary_link", 50);
        defaultSpeedMap.put("unclassified", 60);
        defaultSpeedMap.put("residential", 60);
        // spielstraße
        defaultSpeedMap.put("living_street", 10);
        defaultSpeedMap.put("service", 20);
        // unknown road
        defaultSpeedMap.put("road", 20);
        // forestry stuff
        defaultSpeedMap.put("track", 15);
        
        _speedLimitHandler = new SpeedLimitHandler(this.toString(), defaultSpeedMap, badSurfaceSpeedMap, trackTypeSpeedMap);
        
        forwardKeys.add("goods:forward");
        forwardKeys.add("hgv:forward");
        forwardKeys.add("bus:forward");
        forwardKeys.add("agricultural:forward");
        forwardKeys.add("forestry:forward");
        forwardKeys.add("delivery:forward");
        
        backwardKeys.add("goods:backward");
        backwardKeys.add("hgv:backward");
        backwardKeys.add("bus:backward");
        backwardKeys.add("agricultural:backward");
        backwardKeys.add("forestry:backward");
        backwardKeys.add("delivery:backward");

        init();
    }
    
	public double getDefaultMaxSpeed()
	{
		return 80;
	}

	@Override
    public void createEncodedValues(List<EncodedValue> registerNewEncodedValue, String prefix, int index) {
        super.createEncodedValues(registerNewEncodedValue, prefix, index);
        registerNewEncodedValue.add(priorityWayEncoder = new FactorizedDecimalEncodedValue(prefix + "priority", 3, PriorityCode.getFactor(1), false));
    }
	
	@Override
	public double getMaxSpeed( ReaderWay way ) // runge
	{
		boolean bCheckMaxSpeed = false;
		String maxspeedTag = way.getTag("maxspeed:hgv");
		if (maxspeedTag == null)
		{
			maxspeedTag = way.getTag("maxspeed");
			bCheckMaxSpeed = true;
		}
		
		double maxSpeed = parseSpeed(maxspeedTag);

		double fwdSpeed = parseSpeed(way.getTag("maxspeed:forward"));
		if (fwdSpeed >= 0 && (maxSpeed < 0 || fwdSpeed < maxSpeed))
			maxSpeed = fwdSpeed;

		double backSpeed = parseSpeed(way.getTag("maxspeed:backward"));
		if (backSpeed >= 0 && (maxSpeed < 0 || backSpeed < maxSpeed))
			maxSpeed = backSpeed;

		if (bCheckMaxSpeed)
		{
			double defaultSpeed = _speedLimitHandler.getSpeed(way.getTag("highway"));
			if (defaultSpeed < maxSpeed)
				maxSpeed = defaultSpeed;
		}

		return maxSpeed;
	}

    @Override
    double averageSecondsTo100KmpH() {
        return 10;
    }
	
	protected int getTrackGradeLevel(String grade)
    {
    	if (grade == null)
    		return 0; 
    	 
    	if (grade.contains(";")) // grade3;grade2
    	{
    		int maxGrade = 0; 
    		
    		try
    		{
    			String[] values = grade.split(";"); 
    			for(String v : values)
    			{
    		       int iv = Integer.parseInt(v.replace("grade","").trim());
    		       if (iv > maxGrade)
    		    	   maxGrade = iv;
    			}
    			
    			return maxGrade;
    		}
    		catch(Exception ex)
    		{}
    	}

    	switch(grade)
    	{
    	case "grade":
    	case "grade1":
    		return 1;
    	case "grade2":
    		return 2;
    	case "grade3":
    		return 3;
    	case "grade4":
    		return 4;
    	case "grade5":
    		return 5;
    	case "grade6":
    		return 6;
    	}
    	
    	return 10;
    }
    protected double getSpeed(ReaderWay way )
    {
        String highwayValue = way.getTag("highway");
        Integer speed = _speedLimitHandler.getSpeed(highwayValue);
        if (speed == null)
            throw new IllegalStateException(toString() + ", no speed found for:" + highwayValue);

        if (highwayValue.equals("track"))
        {
            String tt = way.getTag("tracktype");
            if (!Helper.isEmpty(tt))
            {
                Integer tInt = _speedLimitHandler.getTrackTypeSpeed(tt);
                if (tInt != null && tInt != -1)
                    speed = tInt;
            }
        }
        
        String hgvSpeed = way.getTag("maxspeed:hgv");
        if (!Helper.isEmpty(hgvSpeed))
        {
        	try
        	{
        		if ("walk".equals(hgvSpeed))
        			speed = 10;
        		else
        	        speed = Integer.parseInt(hgvSpeed);
        	}
        	catch(Exception ex)
        	{
        		// TODO
        	}
        }
        
     /*   if (way.hasTag("access")) // Runge  //https://www.openstreetmap.org/way/132312559
        {
        	String accessTag = way.getTag("access");
        	if ("destination".equals(accessTag))
        		return 1; 
        }*/

        return speed;
    }

    @Override
    public EncodingManager.Access getAccess(ReaderWay way)
    {
        String highwayValue = way.getTag("highway");
        
        String firstValue = way.getFirstPriorityTag(restrictions);
        if (highwayValue == null)
        {
            if (way.hasTag("route", ferries))
            {
            	 if (restrictedValues.contains(firstValue))
                     return EncodingManager.Access.CAN_SKIP;
                 if (intendedValues.contains(firstValue) ||
                         // implied default is allowed only if foot and bicycle is not specified:
                         firstValue.isEmpty() && !way.hasTag("foot") && !way.hasTag("bicycle"))
                     return EncodingManager.Access.FERRY;
            }
            return EncodingManager.Access.CAN_SKIP;
        }
        
        if ("track".equals(highwayValue))
        {
            String tt = way.getTag("tracktype");
            int grade = getTrackGradeLevel(tt);
            if (grade > maxTrackGradeLevel)
                return EncodingManager.Access.CAN_SKIP;
        }

        if (!_speedLimitHandler.hasSpeedValue(highwayValue))
            return EncodingManager.Access.CAN_SKIP;

        if (way.hasTag("impassable", "yes") || way.hasTag("status", "impassable") || way.hasTag("smoothness", "impassable"))
            return EncodingManager.Access.CAN_SKIP;

        // multiple restrictions needs special handling compared to foot and bike, see also motorcycle
        if (!firstValue.isEmpty()) {
            if (restrictedValues.contains(firstValue) && !getConditionalTagInspector().isRestrictedWayConditionallyPermitted(way))
                return EncodingManager.Access.CAN_SKIP;
            if (intendedValues.contains(firstValue))
                return EncodingManager.Access.WAY;
        }
        
        // do not drive street cars into fords
        boolean carsAllowed = way.hasTag(restrictions, intendedValues);
        if (isBlockFords() && ("ford".equals(highwayValue) || way.hasTag("ford")) && !carsAllowed)
            return EncodingManager.Access.CAN_SKIP;

        // check access restrictions
        if (way.hasTag(restrictions, restrictedValues) && !carsAllowed)
        {
        	// filter special type of access for hgv
        	if (!way.hasTag(hgvAccess, intendedValues))
                return EncodingManager.Access.CAN_SKIP;
        }
        
        String maxwidth = way.getTag("maxwidth"); // Runge added on 23.02.2016
        if (maxwidth != null)
        {
        	try
            {
        		double mwv = Double.parseDouble(maxwidth);
        		if (mwv < 2.0)
                    return EncodingManager.Access.CAN_SKIP;
            }
        	catch(Exception ex)
            {
            	
            }
        }
       
        if (getConditionalTagInspector().isPermittedWayConditionallyRestricted(way))
            return EncodingManager.Access.CAN_SKIP;
        else
            return EncodingManager.Access.WAY;
    }

	@Override
    public IntsRef handleWayTags(IntsRef edgeFlags, ReaderWay way, EncodingManager.Access access, long relationFlags) {
        super.handleWayTags(edgeFlags, way, access, relationFlags);

        priorityWayEncoder.setDecimal(false, edgeFlags, PriorityCode.getFactor(handlePriority(way)));
        return edgeFlags;
    }
    
    protected int handlePriority(ReaderWay way) {
		TreeMap<Double, Integer> weightToPrioMap = new TreeMap<Double, Integer>();
		
		collect(way, weightToPrioMap);
		
		// pick priority with biggest order value
		return weightToPrioMap.lastEntry().getValue();
	}
    
    /**
	 * @param weightToPrioMap
	 *            associate a weight with every priority. This sorted map allows
	 *            subclasses to 'insert' more important priorities as well as
	 *            overwrite determined priorities.
	 */
	protected void collect(ReaderWay way, TreeMap<Double, Integer> weightToPrioMap) { // Runge
		if (way.hasTag("hgv", "designated") || (way.hasTag("access", "designated") && (way.hasTag("goods", "yes") || way.hasTag("hgv", "yes") || way.hasTag("bus", "yes") || way.hasTag("agricultural", "yes") || way.hasTag("forestry", "yes") )))
			weightToPrioMap.put(100d, PriorityCode.BEST.getValue());
		else
		{
			String highway = way.getTag("highway");
			double maxSpeed = getMaxSpeed(way);
			
			if (!Helper.isEmpty(highway))
			{
				if ("motorway".equals(highway) || "motorway_link".equals(highway) || "trunk".equals(highway) || "trunk_link".equals(highway))
					weightToPrioMap.put(100d,  PriorityCode.BEST.getValue());
				else if ("primary".equals(highway) || "primary_link".equals(highway))
					weightToPrioMap.put(100d,  PriorityCode.PREFER.getValue());
				else if ("secondary".equals(highway) || "secondary_link".equals(highway))
					weightToPrioMap.put(100d,  PriorityCode.PREFER.getValue());
				else if ("tertiary".equals(highway) || "tertiary_link".equals(highway))
					weightToPrioMap.put(100d,  PriorityCode.UNCHANGED.getValue());
				else if ("residential".equals(highway) || "service".equals(highway) || "road".equals(highway) || "unclassified".equals(highway))
				{
					 if (maxSpeed > 0 && maxSpeed <= 30)
						 weightToPrioMap.put(120d,  PriorityCode.REACH_DEST.getValue());
					 else
						 weightToPrioMap.put(100d,  PriorityCode.AVOID_IF_POSSIBLE.getValue());
				}
				else if ("living_street".equals(highway))
					 weightToPrioMap.put(100d,  PriorityCode.AVOID_IF_POSSIBLE.getValue());
				else if ("track".equals(highway))
					 weightToPrioMap.put(100d,  PriorityCode.REACH_DEST.getValue());
				else 
					weightToPrioMap.put(40d, PriorityCode.AVOID_IF_POSSIBLE.getValue());
			}
			else	
				weightToPrioMap.put(100d, PriorityCode.UNCHANGED.getValue());
			
			if (maxSpeed > 0)
			{
				// We assume that the given road segment goes through a settlement.
				if (maxSpeed <= 40)
					weightToPrioMap.put(110d, PriorityCode.AVOID_IF_POSSIBLE.getValue());
				else if (maxSpeed <= 50)
					weightToPrioMap.put(110d, PriorityCode.UNCHANGED.getValue());
			}
		}
	}

    public boolean supports(Class<?> feature) {
		if (super.supports(feature))
			return true;
		return PriorityWeighting.class.isAssignableFrom(feature);
	}
    
    @Override
    public String toString()
    {
        return FlagEncoderNames.HEAVYVEHICLE;
    }

	@Override
	public int getVersion() {
		return 2;
	}
}
