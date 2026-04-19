#include "MorseBlinker.h"

MorseBlinker::MorseBlinker(int pin, int unitDelay) {
  _pin = pin;
  _unitDelay = unitDelay;
}

void MorseBlinker::begin() {
  pinMode(_pin, OUTPUT);
}

void MorseBlinker::dot() {
  digitalWrite(_pin, HIGH);
  delay(_unitDelay);
  digitalWrite(_pin, LOW);
  delay(_unitDelay);
}

void MorseBlinker::dash() {
  digitalWrite(_pin, HIGH);
  delay(_unitDelay * 3);
  digitalWrite(_pin, LOW);
  delay(_unitDelay);
}

void MorseBlinker::_pause() {
  delay(_unitDelay * 2);
}

void MorseBlinker::blinkMessage(const char* message) {
  for (int i = 0; message[i] != '\0'; i++) {
    char c = toupper(message[i]);
    switch(c) {
      case 'H': dot(); dot(); dot(); dot(); break;
      case 'E': dot(); break;
      case 'L': dot(); dash(); dot(); dot(); break;
      case 'O': dash(); dash(); dash(); break;
      case 'W': dot(); dash(); dash(); break;
      case 'R': dot(); dash(); dot(); break;
      case 'D': dash(); dot(); dot(); break;
      case 'S': dot(); dot(); dot(); break;
      case ' ': delay(_unitDelay * 4); break;
    }
    _pause();
  }
}
