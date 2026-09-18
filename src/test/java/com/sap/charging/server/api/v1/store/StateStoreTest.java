package com.sap.charging.server.api.v1.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sap.charging.model.Car;
import com.sap.charging.model.ChargingStation;
import com.sap.charging.model.EnergyUtil.Phase;
import com.sap.charging.model.Fuse;
import com.sap.charging.model.FuseTree;
import com.sap.charging.realTime.State;
import com.sap.charging.realTime.StrategyAlgorithmic;
import com.sap.charging.realTime.StrategyAlgorithmicChargeScheduler;
import com.sap.charging.realTime.model.CarAssignment;
import com.sap.charging.realTime.model.CarAssignmentStore;
import com.sap.charging.realTime.model.forecasting.departure.CarDepartureForecast;
import com.sap.charging.realTime.model.forecasting.departure.CarDepartureOracle;
import com.sap.charging.server.api.v1.OptimizeChargingProfilesRequest;
import com.sap.charging.server.api.v1.exception.UnknownCarException;
import com.sap.charging.server.api.v1.exception.UnknownChargingStationException;
import com.sap.charging.sim.Simulation;
import com.sap.charging.sim.common.SimulationUnitTest;
import com.sap.charging.sim.eval.Validation;
import com.sap.charging.util.FileIO;

public class StateStoreTest extends SimulationUnitTest {
	
	List<ChargingStationStore> chargingStations; 
	List<Car> cars; 
	final double maximumSiteLimitKW = 100; 
	List<CarAssignmentStore> carAssignments; 
	
	StrategyAlgorithmic strategy; 
	
	ObjectMapper objectMapper;
	
	@BeforeEach
	public void setup() {
		chargingStations = dataSim.getChargingStations()
				.stream()
				.map(chargingStation -> ChargingStationStore.fromChargingStation(chargingStation))
				.collect(Collectors.toList());
		cars = dataSim.getCars(); 
		carAssignments = new ArrayList<>(); 
		
		strategy = new StrategyAlgorithmic(new CarDepartureOracle());
    	strategy.objectiveEnergyCosts.setWeight(0);
    	strategy.objectiveFairShare.setWeight(1);
    	strategy.setReoptimizeOnStillAvailableAfterExpectedDepartureTimeslot(true);
    	strategy.setRescheduleCarsWith0A(false);
    	
    	objectMapper = new ObjectMapper();
	}
	
	@Test
	public void test_ToState() {
		StateStore stateStore = new StateStore(0, null, chargingStations, maximumSiteLimitKW, cars, null, carAssignments); 
		State state = stateStore.toState(); 
		
		assertEquals(chargingStations.size(), state.nChargingStations); 
		assertEquals(cars.size(), state.cars.size());
		assertEquals(0, state.getCurrentCarAssignments().size()); 
	}
	
	private State getStateFromJsonFile(String filePath) throws JsonMappingException, JsonProcessingException {
		String jsonStateStore = FileIO.readFile(filePath); 
		
		OptimizeChargingProfilesRequest request = objectMapper.readValue(jsonStateStore, OptimizeChargingProfilesRequest.class);  
		StateStore stateStore = request.state; 
		
		return stateStore.toState();
	}
	
	@Test
	public void test_ToState_CarDepartureTime_IsSet() throws JsonMappingException, JsonProcessingException {
		State state = getStateFromJsonFile("src/test/resources/testCasesJSON/StateStoreTest_CarDepartureTime_IsSet.json");
		
		assertEquals(1, state.getCurrentCarAssignments().size());
		Car car = state.getCar(0);
		assertEquals(0, car.getTimestampArrival());
		assertEquals(3600, car.getTimestampDeparture());
		
		CarAssignment carAssignment = state.getCarAssignmentFromAll(car);
		assertEquals(3600, carAssignment.getExpectedDepartureTimeSeconds());
	}
	
	@Test
	public void test_ToStateCarDepartureTime_IsNotSet() throws JsonMappingException, JsonProcessingException {
		State state = getStateFromJsonFile("src/test/resources/testCasesJSON/StateStoreTest_CarDepartureTime_IsNotSet.json");
		
		assertEquals(1, state.getCurrentCarAssignments().size());
		Car car = state.getCar(0);
		assertEquals(0, car.getTimestampArrival());
		assertEquals(0, car.getTimestampDeparture());
		
		CarAssignment carAssignment = state.getCarAssignmentFromAll(car);
		int defaultDepartureTimeSeconds = CarDepartureForecast.getDefaultCarDepartureForecast().getExpectedDepartureTimeSeconds(null, null);
		assertEquals(defaultDepartureTimeSeconds, carAssignment.getExpectedDepartureTimeSeconds());
		assertEquals(17*3600 + 3*60, carAssignment.getExpectedDepartureTimeSeconds());
	}
	
	@Test
	public void test_InvalidCarAssignment_invalidCarID() {
		carAssignments.add(new CarAssignmentStore(999, chargingStations.get(0).id)); 
		try {
			new StateStore(0, null, chargingStations, maximumSiteLimitKW, cars, null, carAssignments); 
			fail("Should have failed with unknown car ID"); 
		}
		catch (UnknownCarException e) {}
	}
	
	@Test
	public void test_InvalidCarAssignment_invalidChargingStation() {
		carAssignments.add(new CarAssignmentStore(cars.get(0).getId(), 999)); 
		try {
			new StateStore(0, null, chargingStations, maximumSiteLimitKW, cars, null, carAssignments); 
			fail("Should have failed with unknown chargingStation ID"); 
		}
		catch (UnknownChargingStationException e) {}
	}
	
	@Test
	public void test_PassFuseTree_ChargingStationParent_NotNull() throws JsonMappingException, JsonProcessingException {
		State state = getStateFromJsonFile("src/test/resources/testCasesJSON/StateStoreTest_FuseTooSmall_4_Cars.json");
		
		assertEquals(2, state.getChargingStationsOccupied().size()); 
		for (ChargingStation station : state.getChargingStationsOccupied()) {
			assertNotNull(station.getParent());
		}
	}
	
	@Test
	public void test_OptimizeState_WithUnassignedCars() throws JsonMappingException, JsonProcessingException {
		State state = getStateFromJsonFile("src/test/resources/testCasesJSON/StateStoreTest_FuseTooSmall_4_Cars.json");
		
    	Simulation.verbosity = 0; 
		strategy.reactReoptimize(state);
		
		// All cars should be planned to be fully charged 
		for (CarAssignment carAssignment : state.getCurrentCarAssignments()) {
			Car car = carAssignment.car; 
			ChargingStation chargingStation = carAssignment.chargingStation; 
			assertEquals(car.getMissingCapacity(), strategy.getScheduler().getPlannedCapacity(chargingStation, car, state.currentTimeSeconds), 1e-8); 
		}
		
		// However, cars should not charge at the same time
		Car car1 = state.getCar(0); 
		Car car2 = state.getCar(1); 
		int currentTimeslot = state.currentTimeslot; 
		assertNotEquals(car1.getCurrentPlan()[currentTimeslot], car2.getCurrentPlan()[currentTimeslot]);
	}
	
	
	@Test
	public void test_OptimizeState_SinglePhaseCar_StationWithPhaseRotation_SinglePhaseFuse() throws JsonMappingException, JsonProcessingException {
		// See also StrategyAlgorithmicTest.testRescheduleCar_SinglePhaseCar_StationWithPhaseRotation_SinglePhaseFuse
		State state = getStateFromJsonFile("src/test/resources/testCasesJSON/StateStoreTest_SinglePhaseCar_StationWithPhaseRotation_SinglePhaseFuse.json");
		
		Simulation.verbosity = 0; 
		strategy.reactReoptimize(state);
		Car car = state.getCar(0); 
		
		for (int k=0; k<96; k++) {
			assertEquals(0, car.getCurrentPlan()[k], 1e-8); 
		}
	}

	@Test
	public void test_OptimizeState_Two_SinglePhaseCars_Two_ThreePhasedStations_DifferentPhasesConsuming() throws JsonMappingException, JsonProcessingException {
		State state = getStateFromJsonFile("src/test/resources/testCasesJSON/StateStoreTest_Two_SinglePhaseCars_Two_ThreePhasedStations_DifferentPhasesConsuming.json");
		
		Simulation.verbosity = 0; 
		strategy.reactReoptimize(state);
		Car car = state.getCar(1); 
		Car car1 = state.getCar(2); 
		
		for (int k=0; k<96; k++) {
			assertEquals(8, car.getCurrentPlan()[k], 1e-8);
			assertEquals(8, car1.getCurrentPlan()[k], 1e-8);
		}
	}

	/**
	 * Reproduces the Parking TCA Prosperi (Transdev) topology: a root fuse of 26.09A per phase
	 * (18000W / 230V / 3 phases) with five single-phase 32A cars/stations spread over the three
	 * grid phases so that TWO phases (L1 and L2) are over the fuse at the same time, and the
	 * third (L3) is over it as well: A and C on L1, B and D on L2, E on L3.
	 * <br>
	 * Priorities are forced (via decreasing missingCapacity, i.e. increasing startCapacity) to
	 * A &lt; C &lt; B &lt; D &lt; E (lowest priority first), so the resolver picks A first on the
	 * worst phase (L1, tie-broken over L2 by FuseTreeException), then must pick B next even
	 * though C (checked first, but drawing 0A on L2) is not the fix. Before the upstream fix
	 * (bacf516), C would be dropped from the candidate list without being rescheduled, the list
	 * would run out and StrategyAlgorithmic.handleViolation would throw
	 * "Rescheduled all n=5 cars at k=0 but violation is not fixed!".
	 */
	@Test
	public void test_OptimizeState_Prosperi_SinglePhaseCars_TwoPhasesOverFuse() throws JsonMappingException, JsonProcessingException {
		State state = getStateFromJsonFile("src/test/resources/testCasesJSON/StateStoreTest_Prosperi_SinglePhaseCars_TwoPhasesOverFuse.json");

		Simulation.verbosity = 0;
		strategy.reactReoptimize(state);

		final double fusePhaseLimit = 26.08695652173913; // 18000W / 230V / 3 phases
		final double epsilon = 1e-6;

		ChargingStation stationA = state.getChargingStation(1);
		ChargingStation stationC = state.getChargingStation(2);
		ChargingStation stationB = state.getChargingStation(3);
		ChargingStation stationD = state.getChargingStation(4);
		ChargingStation stationE = state.getChargingStation(5);

		// The fuse must never be exceeded on any grid phase, at any timeslot
		for (int k = 0; k < 96; k++) {
			double[] fromA = Validation.getCurrentPerGridPhase(stationA, state, k);
			double[] fromC = Validation.getCurrentPerGridPhase(stationC, state, k);
			double[] fromB = Validation.getCurrentPerGridPhase(stationB, state, k);
			double[] fromD = Validation.getCurrentPerGridPhase(stationD, state, k);
			double[] fromE = Validation.getCurrentPerGridPhase(stationE, state, k);

			double sumL1 = fromA[0] + fromC[0] + fromB[0] + fromD[0] + fromE[0];
			double sumL2 = fromA[1] + fromC[1] + fromB[1] + fromD[1] + fromE[1];
			double sumL3 = fromA[2] + fromC[2] + fromB[2] + fromD[2] + fromE[2];

			assertTrue(sumL1 <= fusePhaseLimit + epsilon, "L1 over fuse at k=" + k + ": " + sumL1 + "A");
			assertTrue(sumL2 <= fusePhaseLimit + epsilon, "L2 over fuse at k=" + k + ": " + sumL2 + "A");
			assertTrue(sumL3 <= fusePhaseLimit + epsilon, "L3 over fuse at k=" + k + ": " + sumL3 + "A");
		}

		// At k=0: A and B are cut to 0A (lowest priority on the worst phase at each step),
		// C, D and E are reduced to exactly the fuse limit (not to 0, not left at 32A)
		Car carA = state.getCar(1);
		Car carC = state.getCar(2);
		Car carB = state.getCar(3);
		Car carD = state.getCar(4);
		Car carE = state.getCar(5);

		assertEquals(0, carA.getCurrentPlan()[0], epsilon);
		assertEquals(0, carB.getCurrentPlan()[0], epsilon);
		assertEquals(fusePhaseLimit, carC.getCurrentPlan()[0], epsilon);
		assertEquals(fusePhaseLimit, carD.getCurrentPlan()[0], epsilon);
		assertEquals(fusePhaseLimit, carE.getCurrentPlan()[0], epsilon);
	}

	@Test
	public void test_OptimizeState_ThreePhaseCar_SinglePhaseStation() throws JsonMappingException, JsonProcessingException {
		State state = getStateFromJsonFile("src/test/resources/testCasesJSON/StateStoreTest_ThreePhaseCar_SinglePhaseStation.json");
		
    	Simulation.verbosity = 0; 
		strategy.reactReoptimize(state);
		Car car = state.getCar(0); 
		ChargingStation station = state.getChargingStation(0); 
		
		assertEquals(3, car.sumUsedPhases, 0); 
		
		StrategyAlgorithmicChargeScheduler scheduler = strategy.getScheduler(); 
		assertEquals(1, scheduler.getSumUsedPhases(station, car), 0); 
		
		// Should be fully planned
		double desiredCapacity = car.getMissingCapacity(); 
		double plannedCapacity = scheduler.getPlannedCapacity(station, car, state.currentTimeSeconds);
		assertEquals(desiredCapacity, plannedCapacity, 1e-8); 
		
	}
	
	@Test
	public void test_OptimizeState_ThreePhaseCar_SinglePhaseStationWithPhaseRotation() throws JsonMappingException, JsonProcessingException {
		State state = getStateFromJsonFile("src/test/resources/testCasesJSON/StateStoreTest_ThreePhaseCar_SinglePhaseStationWithPhaseRotation.json");
		
    	Simulation.verbosity = 0; 
		strategy.reactReoptimize(state);
		Car car = state.getCar(0); 
		ChargingStation station = state.getChargingStation(0); 
		
		assertEquals(3, car.sumUsedPhases, 0); 
		
		assertEquals(true, station.isPhaseAtStationConnectedInFuseTree(Phase.PHASE_1));
		assertEquals(false, station.isPhaseAtStationConnectedInFuseTree(Phase.PHASE_2));
		assertEquals(false, station.isPhaseAtStationConnectedInFuseTree(Phase.PHASE_3));
		
		assertEquals(false, station.isPhaseAtGridConnectedInFuseTree(Phase.PHASE_1));
		assertEquals(true, station.isPhaseAtGridConnectedInFuseTree(Phase.PHASE_2));
		assertEquals(false, station.isPhaseAtGridConnectedInFuseTree(Phase.PHASE_3));
		
		
		StrategyAlgorithmicChargeScheduler scheduler = strategy.getScheduler(); 
		assertEquals(1, scheduler.getSumUsedPhases(station, car), 0); 
		
		// Should be fully planned
		double desiredCapacity = car.getMissingCapacity(); 
		double plannedCapacity = scheduler.getPlannedCapacity(station, car, state.currentTimeSeconds);
		assertEquals(desiredCapacity, plannedCapacity, 1e-8); 
		
	}
	
	@Test
	public void test_FuseTree_And_ChargingStations_Passed_ShouldError() {
		
		ChargingStation station = new ChargingStation(); 
		station.setID(0);
		station.fusePhase1 = 10.666;
		station.fusePhase2 = 10.666;
		station.fusePhase3 = 10.666;
		
		Fuse childFuse = new Fuse(1, 10.666); 
		childFuse.addChild(station);
		Fuse rootFuse = new Fuse(0, 2.89); 
		rootFuse.addChild(childFuse);
		
		FuseTree fuseTree = new FuseTree(rootFuse, 1); 
		
		chargingStations = new ArrayList<ChargingStationStore>(); 
		chargingStations.add(ChargingStationStore.fromChargingStation(station)); 
		
		Car car = cars.get(0);
		cars = new ArrayList<Car>(); 
		cars.add(car); 
		
		
		carAssignments.add(new CarAssignmentStore(car.getId(), station.getId())); 
		
		int currentTimeSeconds = car.timestampArrival.toSecondOfDay();  
		
		try {
			new StateStore(currentTimeSeconds, fuseTree, chargingStations, null, cars, null, carAssignments); 
			fail("Should have failed when passing fuseTree and a separate chargingStations array"); 
		}
		catch (Exception e) {}
		
		
		
	}
	
	
	
	
	
}






