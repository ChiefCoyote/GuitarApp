A mobile app to track how a user plays a chord and provide real-time visual feedback.
Requires an Android device with a camera, targets android 14

# How to Install

Download Android Studio

Clone the repository and open it in Android Studio

Enable Developer mode on the chosen mobile device (instructions are device dependent)

Enable USB debugging in developer setting

Plug in the device

Build and run the code

# Creating a custom chord text file

Must be a standard text file

Must be on a single line

Begin with the chord name, must not use special characters, can have a space e.g. "D chord"

Follow with a '|' character

Next comes 6 groups of 'x-x' separated by commas
These are to represent the 6 strings, first is which finger should press the string and the second is which fret is being pressed

If a chord is muted use an 'x' if it is open use a '0'

In both of these cases the finger should be '_'

## Example:
D Chord|3-2,4-3,2-2,_-0,_-x,_-x
