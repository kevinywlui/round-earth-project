#ifndef MorseBlinker_h
#define MorseBlinker_h

#include "Arduino.h"

class MorseBlinker {
  public:
    MorseBlinker(int pin, int unitDelay = 200);
    void begin(); // Added begin() method
    void dot();
    void dash();
    void blinkMessage(const char* message);
  private:
    int _pin;
    int _unitDelay;
    void _pause();
};

#endif
