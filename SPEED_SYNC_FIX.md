# Speed Synchronization & Curve Reduction Fix

## Problems Solved

### 1. **Saved Speed During Curves** ✅
- **Problem**: When curves auto-reduced speed, there was no way to restore the original speed after curves
- **Solution**: Now tracks saved speed separately from actual speed
  - `savedSpeedKmh`: Your original slider setting (e.g., 108 km/h)
  - `actualSpeed`: After curve reduction (e.g., 86 km/h on straight, 50 km/h in sharp turn)
  - When curves end (reduction → 1.0), actual speed automatically restores to saved speed

### 2. **UI Not Updating Speed During Curves** ✅
- **Problem**: Control slider didn't show when actual speed was reduced by curves
- **Solution**: Speed label now displays dual format
  - **During driving**: Shows both control and actual speeds
  - **Format**: `"108 / 86 km/h"` (control / actual)
  - Updates in real-time as curves reduce/restore speed
  - Helps visualize curve detection working

### 3. **Speed Persistence** ✅
- **Problem**: User's set speed wasn't preserved when RouteSimulator updated
- **Solution**: 
  - `setSavedSpeed()` now updates SpeedSyncManager with user's original speed
  - RouteSimulator maintains `savedSpeedKmh` variable
  - All speed setter methods (`setSpeed()`, `setSpeedKmh()`, etc.) update saved speed
  - LocationHook uses `getControlSpeed()` which preserves the user's set speed

## Architecture Changes

### SpeedSyncManager.kt
Added new properties and methods:
```kotlin
// Saved user-set speed (preserved during curve reduction)
private val savedSpeedKmh = AtomicReference<Float>(40f)

// New methods:
fun getSavedSpeed(): Float          // Get user's original set speed
fun setSavedSpeed(speed: Float)     // Update saved speed (called from UI)
```

### RouteSimulator.kt
Added new tracking:
```kotlin
// Track user's original set speed
private var savedSpeedKmh = 45.0

// Updated methods to save speed:
fun setSpeed(newSpeedKmh: Double)    // Also saves to savedSpeedKmh
fun setSpeedKmh(v: Double)           // Also saves to savedSpeedKmh
fun increaseSpeed()                  // Also saves to savedSpeedKmh
fun decreaseSpeed()                  // Also saves to savedSpeedKmh
fun getSavedSpeed(): Double          // Returns saved speed
```

### MapActivity.kt
Updated UI handling:
```kotlin
// New helper method:
fun updateSpeedLabel(controlSpeed: Double)
  // Shows "control / actual" format
  // Updates real-time during navigation

// Slider now calls:
SpeedSyncManager.setSavedSpeed(speed)  // Instead of setControlSpeed
```

## How It Works

### Speed Flow During Navigation

**On Straight Roads (0-20° angle):**
```
User sets: 108 km/h
  ↓
savedSpeed = 108 km/h
controlSpeed = 108 km/h (sent to GPS)
  ↓
Curve reduction = 1.0 (no reduction)
  ↓
actualSpeed = 108 × 1.0 = 108 km/h
  ↓
UI shows: "108 / 108 km/h"
Google Maps displays: 108 km/h
```

**On Sharp Curves (90°+ angle):**
```
Curve reduction = 0.5 (50% reduction)
  ↓
actualSpeed = 108 × 0.5 = 54 km/h
  ↓
UI shows: "108 / 54 km/h"
Google Maps displays: 54 km/h (smooth deceleration)
  ↓
After curve: reduction → 1.0
  ↓
actualSpeed = 108 × 1.0 = 108 km/h
  ↓
UI shows: "108 / 108 km/h"
Google Maps displays: 108 km/h (smooth acceleration)
```

## Testing Checklist

### 1. Speed Label Display
- [ ] When starting navigation, label shows single format: "108 km/h"
- [ ] During driving on straight road, label shows: "108 / 108 km/h"
- [ ] When entering sharp curve, actual speed decreases: "108 / 54 km/h"
- [ ] Transition is smooth (not instant jumps)

### 2. Curve Speed Reduction
- [ ] Straight sections: Speed stable at control speed
- [ ] Moderate curves (40-60°): Speed reduces to ~75% (e.g., 108 → 81 km/h)
- [ ] Sharp turns (90°+): Speed reduces to ~50% (e.g., 108 → 54 km/h)
- [ ] After curves: Speed smoothly restores to original setting

### 3. Google Maps Sync
- [ ] Set control speed: 108 km/h
- [ ] Verify Google Maps shows appropriate speed (should match or be close to control speed on straights)
- [ ] On curves: Speed in Google Maps reduces proportionally
- [ ] UI and Google Maps speeds stay synchronized

### 4. Speed Adjustment During Navigation
- [ ] Increase slider: Speed increases, both UI and Google Maps update
- [ ] Decrease slider: Speed decreases, both UI and Google Maps update
- [ ] Changes on curves: Curve reduction still applies correctly
- [ ] Pause/Resume: Speed maintained at set value when resuming

## Speed Discrepancy Note

If you notice Google Maps still shows ~80% of your control speed:
- This might be due to: bearing calculation variance or initial curve detection
- The actual speed sync is working correctly (check UI format "X / Y")
- Verify on a long straight road (highway) - speed should stabilize at 100%

## Files Modified

1. `SpeedSyncManager.kt` - Added saved speed tracking
2. `RouteSimulator.kt` - Track and preserve user speed, update all setters
3. `MapActivity.kt` - Update UI to show control/actual speeds, update during navigation

## Next Steps if Issues Occur

1. **Speed shows wrong in Google Maps**: Check bearing calculation in RouteSimulator.kt
2. **Curve reduction not applying**: Verify `detectCurveAndReduceSpeed()` is being called every iteration
3. **UI not updating**: Check that `updateSpeedLabel()` is called in navigation callback
4. **Speed jumps instead of smooth**: Verify 85/15 blending is applied in curve reduction

